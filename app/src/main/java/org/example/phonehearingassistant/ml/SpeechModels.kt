package org.example.phonehearingassistant.ml

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import java.io.Closeable
import kotlin.math.*

/** All samples are mono, floating-point PCM. No network or platform speech service is used. */
data class PcmAudio(val samples: FloatArray, val sampleRate: Int)

data class Recognition(val text: String, val isFinal: Boolean)

/**
 * Explicit native-resource owner. Loading performs disk I/O and must run off the main thread. Keep
 * denoise/ASR calls on their respective serial workers; TTS can run on a separate worker. close()
 * waits for in-flight JNI calls before freeing native resources.
 */
class SpeechModels(context: Context) : Closeable {
    private val assets = context.applicationContext.assets
    private val denoiseLock = Any()
    private val asrLock = Any()
    private val ttsLock = Any()
    @Volatile private var closed = false
    private var denoiser: OnlineSpeechDenoiser? = null
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var tts: OfflineTts? = null
    private var previousText = ""
    @Volatile
    var denoiserName: String = "off"
        private set

    @Volatile
    var asrName: String = ""
        private set

    private fun checkOpen() = check(!closed) { "SpeechModels is closed" }

    private fun requireAsset(path: String) {
        assets.open(path).use { check(it.read() >= 0) { "Empty model asset: $path" } }
    }

    fun loadDenoiser(kind: String = "gtcrn") =
        synchronized(denoiseLock) {
            checkOpen()
            require(kind in setOf("off", "gtcrn", "dpdfnet")) { "Unknown denoiser: $kind" }
            if (kind == denoiserName) return@synchronized
            val model = OfflineSpeechDenoiserModelConfig(numThreads = 1, provider = "cpu")
            when (kind) {
                "gtcrn" -> {
                    val path = "models/denoise/gtcrn.onnx"
                    requireAsset(path)
                    model.gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = path)
                }
                "dpdfnet" -> {
                    val path = "models/denoise/dpdfnet2.onnx"
                    requireAsset(path)
                    model.dpdfnet =
                        OfflineSpeechDenoiserDpdfNetModelConfig(
                            model = path,
                            attenuationLimitDb = 24f,
                        )
                }
            }
            // Build the replacement before releasing a working instance.
            val next =
                if (kind == "off") null
                else
                    OnlineSpeechDenoiser(
                        assetManager = assets,
                        config = OnlineSpeechDenoiserConfig(model = model),
                    )
            if (next != null && next.sampleRate != SAMPLE_RATE) {
                next.release()
                error("This build only supports 16 kHz denoisers")
            }
            denoiser?.release()
            denoiser = next
            denoiserName = kind
        }

    /**
     * Variable output length: enqueue returned samples and consume full DSP blocks. Initial empty
     * output is normal; never replace it with dry audio (would desynchronize).
     */
    fun denoise(samples: FloatArray): FloatArray =
        synchronized(denoiseLock) {
            checkOpen()
            if (samples.isEmpty()) return@synchronized FloatArray(0)
            denoiser?.run(sanitize(samples), SAMPLE_RATE)?.samples ?: sanitize(samples)
        }

    fun resetDenoiser() =
        synchronized(denoiseLock) {
            checkOpen()
            denoiser?.reset()
        }

    /** zh: smaller 2025 Chinese CTC; bilingual: larger Chinese/English transducer. */
    fun loadAsr(kind: String = "zh") =
        synchronized(asrLock) {
            checkOpen()
            require(kind == "zh" || kind == "bilingual") { "Unknown ASR model: $kind" }
            if (recognizer != null && kind == asrName) return@synchronized
            val config =
                if (kind == "zh") {
                    requireAsset("models/asr-zh/model.int8.onnx")
                    requireAsset("models/asr-zh/tokens.txt")
                    OnlineModelConfig(
                        zipformer2Ctc =
                            OnlineZipformer2CtcModelConfig(model = "models/asr-zh/model.int8.onnx"),
                        tokens = "models/asr-zh/tokens.txt",
                        numThreads = 2,
                        provider = "cpu",
                    )
                } else {
                    val path = "models/asr-bilingual"
                    listOf(
                            "encoder-epoch-99-avg-1.int8.onnx",
                            "decoder-epoch-99-avg-1.int8.onnx",
                            "joiner-epoch-99-avg-1.int8.onnx",
                            "tokens.txt",
                        )
                        .forEach { requireAsset("$path/$it") }
                    OnlineModelConfig(
                        transducer =
                            OnlineTransducerModelConfig(
                                encoder = "$path/encoder-epoch-99-avg-1.int8.onnx",
                                decoder = "$path/decoder-epoch-99-avg-1.int8.onnx",
                                joiner = "$path/joiner-epoch-99-avg-1.int8.onnx",
                            ),
                        tokens = "$path/tokens.txt",
                        modelType = "zipformer",
                        numThreads = 2,
                        provider = "cpu",
                    )
                }
            val next =
                OnlineRecognizer(
                    assetManager = assets,
                    config =
                        OnlineRecognizerConfig(
                            featConfig =
                                FeatureConfig(
                                    sampleRate = SAMPLE_RATE,
                                    featureDim = 80,
                                    dither = 0f,
                                ),
                            modelConfig = config,
                            enableEndpoint = true,
                            endpointConfig =
                                EndpointConfig(
                                    rule1 = EndpointRule(false, 2.4f, 0f),
                                    rule2 = EndpointRule(true, 0.8f, 0f),
                                    rule3 = EndpointRule(false, 0f, 12f),
                                ),
                            decodingMethod = "greedy_search",
                        ),
                )
            val nextStream =
                try {
                    next.createStream()
                } catch (t: Throwable) {
                    next.release()
                    throw t
                }
            stream?.release()
            recognizer?.release()
            recognizer = next
            stream = nextStream
            previousText = ""
            asrName = kind
        }

    /** Returns partial updates and final utterances; null means nothing changed. */
    fun accept(samples: FloatArray): Recognition? =
        synchronized(asrLock) {
            checkOpen()
            val r = checkNotNull(recognizer) { "Call loadAsr() first" }
            val s = checkNotNull(stream)
            s.acceptWaveform(sanitize(samples), SAMPLE_RATE)
            while (r.isReady(s)) r.decode(s)
            val text = r.getResult(s).text.trim()
            val endpoint = r.isEndpoint(s)
            val changed = text != previousText
            previousText = text
            if (endpoint) {
                r.reset(s)
                previousText = ""
            }
            if (text.isEmpty() || (!changed && !endpoint)) null else Recognition(text, endpoint)
        }

    fun resetAsr() =
        synchronized(asrLock) {
            checkOpen()
            stream?.let { recognizer?.reset(it) }
            previousText = ""
        }

    fun loadTts() =
        synchronized(ttsLock) {
            checkOpen()
            if (tts != null) return@synchronized
            val path = "models/tts-melo"
            listOf(
                    "model.int8.onnx",
                    "tokens.txt",
                    "lexicon.txt",
                    "number.fst",
                    "date.fst",
                    "phone.fst",
                    "new_heteronym.fst",
                )
                .forEach { requireAsset("$path/$it") }
            tts =
                OfflineTts(
                    assetManager = assets,
                    config =
                        OfflineTtsConfig(
                            model =
                                OfflineTtsModelConfig(
                                    vits =
                                        OfflineTtsVitsModelConfig(
                                            model = "$path/model.int8.onnx",
                                            lexicon = "$path/lexicon.txt",
                                            tokens = "$path/tokens.txt",
                                        ),
                                    numThreads = 2,
                                    provider = "cpu",
                                ),
                            ruleFsts =
                                listOf("date", "number", "phone", "new_heteronym").joinToString(
                                    ","
                                ) {
                                    "$path/$it.fst"
                                },
                            maxNumSentences = 1,
                            silenceScale = 0.2f,
                        ),
                )
        }

    /** Raw TTS must pass through resampling, hearing DSP and output limiter before playback. */
    fun synthesize(text: String, speed: Float = 1f): PcmAudio =
        synchronized(ttsLock) {
            checkOpen()
            val engine = checkNotNull(tts) { "Call loadTts() first" }
            val clean = text.trim()
            require(clean.length <= 300) {
                "Split long text into utterances of at most 300 characters"
            }
            if (clean.isEmpty()) return@synchronized PcmAudio(FloatArray(0), SAMPLE_RATE)
            val audio = engine.generate(clean, sid = 0, speed = speed.coerceIn(0.7f, 1.4f))
            PcmAudio(sanitize(audio.samples), audio.sampleRate)
        }

    override fun close() {
        // Mark closed first so no new work can enter once locks become available.
        closed = true
        synchronized(denoiseLock) {
            denoiser?.release()
            denoiser = null
        }
        synchronized(asrLock) {
            stream?.release()
            stream = null
            recognizer?.release()
            recognizer = null
        }
        synchronized(ttsLock) {
            tts?.release()
            tts = null
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000

        private fun sanitize(samples: FloatArray): FloatArray {
            if (samples.all { it.isFinite() && it in -1f..1f }) return samples
            return FloatArray(samples.size) { i ->
                if (samples[i].isFinite()) samples[i].coerceIn(-1f, 1f) else 0f
            }
        }

        /** Windowed-sinc conversion with an anti-alias lowpass, for complete TTS utterances. */
        fun resample16k(audio: PcmAudio): FloatArray {
            require(audio.sampleRate in 8000..192000)
            if (audio.sampleRate == SAMPLE_RATE) return audio.samples
            if (audio.samples.isEmpty()) return FloatArray(0)
            val ratio = audio.sampleRate.toDouble() / SAMPLE_RATE
            val count = (audio.samples.size / ratio).roundToInt()
            val cutoff = 0.45 * min(1.0, 1.0 / ratio)
            val radius = 32
            return FloatArray(count) { i ->
                val center = i * ratio
                val origin = floor(center).toInt()
                var total = 0.0
                var norm = 0.0
                for (k in (origin - radius + 1)..(origin + radius)) {
                    val d = k - center
                    if (abs(d) >= radius) continue
                    val sinc =
                        if (abs(d) < 1e-9) 2 * cutoff else sin(2 * PI * cutoff * d) / (PI * d)
                    val w = sinc * (0.5 + 0.5 * cos(PI * d / radius))
                    norm += w
                    if (k in audio.samples.indices) total += audio.samples[k] * w
                }
                if (abs(norm) > 1e-12) (total / norm).toFloat().coerceIn(-1f, 1f) else 0f
            }
        }
    }
}
