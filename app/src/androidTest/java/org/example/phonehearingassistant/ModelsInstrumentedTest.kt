package org.example.phonehearingassistant

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.*
import org.example.phonehearingassistant.dsp.DspConfig
import org.example.phonehearingassistant.dsp.HearingDsp
import org.example.phonehearingassistant.ml.SpeechModels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real AAR/JNI + APK asset smoke tests. No AudioRecord/AudioTrack or speaker output is created.
 * Timings describe this test device/emulator only, never Bluetooth or hearing-aid latency.
 */
@RunWith(AndroidJUnit4::class)
class ModelsInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun environment() {
        Log.i(
            TAG,
            "environment model=${Build.MODEL}, api=${Build.VERSION.SDK_INT}, abi=${Build.SUPPORTED_ABIS.joinToString()}, emulator=${Build.FINGERPRINT.contains("generic")}; inference only, no playback",
        )
    }

    private fun verifyDenoiser(kind: String) {
        environment()
        SpeechModels(context).use { models ->
            var start = SystemClock.elapsedRealtimeNanos()
            models.loadDenoiser(kind)
            Log.i(TAG, "$kind loadMs=${elapsedMs(start)}")
            start = SystemClock.elapsedRealtimeNanos()
            var outputCount = 0
            var nonzero = false
            var seed = 1234567
            val outputLengths = ArrayList<Int>()
            for (frameIndex in 0 until 125) {
                val frame =
                    FloatArray(256) { i ->
                        seed = seed * 1664525 + 1013904223
                        val noise = ((seed ushr 8) / 16777216f - 0.5f) * 0.04f
                        val t = (frameIndex * 256 + i) / 16000.0
                        (0.025 * sin(2 * PI * 440 * t) + noise).toFloat()
                    }
                val output = models.denoise(frame)
                if (frameIndex < 8) outputLengths.add(output.size)
                assertTrue("$kind must output finite PCM", output.all { it.isFinite() })
                nonzero = nonzero || output.any { abs(it) > 1e-8f }
                outputCount += output.size
            }
            assertTrue("$kind never emitted audio", outputCount > 16000)
            assertTrue("$kind emitted more samples than its input", outputCount <= 32000)
            assertTrue("$kind emitted only zeros", nonzero)
            Log.i(
                TAG,
                "$kind inputSamples=32000 outputSamples=$outputCount firstOutputLengths=$outputLengths inferMs=${elapsedMs(start)}",
            )
            models.resetDenoiser()
            assertTrue(models.denoise(FloatArray(256)).all { it.isFinite() })
            models.loadDenoiser("off")
            val dry = floatArrayOf(-0.1f, 0f, 0.1f)
            assertArrayEquals(dry, models.denoise(dry), 0f)
        }
    }

    @Test(timeout = 180000) fun gtcrnUsesPackagedNativeModel() = verifyDenoiser("gtcrn")

    @Test(timeout = 180000) fun dpdfnetUsesPackagedNativeModel() = verifyDenoiser("dpdfnet")

    @Test(timeout = 240000)
    fun bothStreamingRecognizersAcceptSilenceAndReset() {
        environment()
        for (kind in listOf("zh", "bilingual")) {
            SpeechModels(context).use { models ->
                var start = SystemClock.elapsedRealtimeNanos()
                models.loadAsr(kind)
                assertEquals(kind, models.asrName)
                Log.i(TAG, "ASR $kind loadMs=${elapsedMs(start)}")
                start = SystemClock.elapsedRealtimeNanos()
                var updates = 0
                // Enough context to execute multiple real encoder/decoder calls and endpoint logic.
                repeat(200) {
                    models.accept(FloatArray(256))?.let { result ->
                        assertTrue(
                            "ASR produced an unbounded silence result",
                            result.text.length < 1000,
                        )
                        updates++
                    }
                }
                models.resetAsr()
                models.accept(FloatArray(256))
                Log.i(
                    TAG,
                    "ASR $kind silenceSamples=51200 updates=$updates inferMs=${elapsedMs(start)}",
                )
            }
        }
    }

    @Test(timeout = 300000)
    fun chineseTtsResamplesAndPassesHearingLimiterWithoutPlayback() {
        environment()
        SpeechModels(context).use { models ->
            var start = SystemClock.elapsedRealtimeNanos()
            models.loadTts()
            Log.i(TAG, "MeloTTS loadMs=${elapsedMs(start)}")
            start = SystemClock.elapsedRealtimeNanos()
            val audio = models.synthesize("你好，助听测试。")
            val synthMs = elapsedMs(start)
            assertTrue("TTS sample rate must be valid", audio.sampleRate in 8000..96000)
            assertTrue("TTS must produce audio", audio.samples.size > audio.sampleRate / 5)
            assertTrue("TTS produced invalid PCM", audio.samples.all { it.isFinite() })
            assertTrue("TTS produced only silence", audio.samples.any { abs(it) > 1e-5f })
            val resampled = SpeechModels.resample16k(audio)
            val expected = audio.samples.size.toDouble() * 16000 / audio.sampleRate
            assertEquals(expected, resampled.size.toDouble(), 1.1)
            assertTrue(resampled.all { it.isFinite() })
            val cfg =
                DspConfig().apply {
                    mode = 3
                    ceilingDb = -24f
                    masterGainDb = 12f
                    for (channel in 0..1) {
                        gainDb[channel].fill(18f)
                        targetLowHz[channel] = 300f
                        targetHighHz[channel] = 2800f
                    }
                }
            val dsp = HearingDsp(cfg)
            val ceiling = 10.0.pow(cfg.ceilingDb / 20.0).toFloat()
            var peak = 0f
            var cursor = 0
            while (cursor < resampled.size + 256) {
                val input =
                    FloatArray(256) { i ->
                        if (cursor + i < resampled.size) resampled[cursor + i] else 0f
                    }
                val output = dsp.process(input)
                assertEquals(512, output.size)
                assertTrue("TTS -> DSP produced invalid PCM", output.all { it.isFinite() })
                for (sample in output) {
                    peak = max(peak, abs(sample))
                    assertTrue("DSP exceeded its digital ceiling", abs(sample) <= ceiling + 1e-6f)
                }
                cursor += 256
            }
            assertTrue("Mapped/limited TTS is empty", peak > 1e-6f)
            Log.i(
                TAG,
                "MeloTTS outputSamples=${audio.samples.size} rate=${audio.sampleRate} synthMs=$synthMs resampledSamples=${resampled.size} mappedPeak=$peak ceiling=$ceiling; no playback",
            )
        }
    }

    @Test
    fun releasedNativeOwnerRejectsNewInference() {
        val models = SpeechModels(context)
        models.close()
        models.close() // Destruction is idempotent.
        try {
            models.denoise(FloatArray(256))
            fail("A released owner must not accept new inference")
        } catch (_: IllegalStateException) {
            // Prevent accidental JNI entry after the native owner has closed.
        }
    }

    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    companion object {
        private const val TAG = "ModelSmoke"
    }
}
