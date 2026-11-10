package org.example.phonehearingassistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.sin

/** Minimal low-level audio routing demo. Not a calibrated or clinically safe hearing-aid processor. */
object AudioEngine {
    @Volatile private var active = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null

    @Synchronized fun start(context: Context, level: Float) {
        stop()
        val rate = 16000
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minIn <= 0 || minOut <= 0) return
        val input = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 2048) * 2)
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minOut, 2048) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        recorder = input
        track = output
        active = true
        worker = Thread {
            val buffer = ShortArray(512)
            try {
                input.startRecording()
                output.play()
                while (active) {
                    val count = input.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        val gain = (level.coerceIn(0.05f, 0.30f) * 3.0f).coerceAtMost(0.9f)
                        for (i in 0 until count) {
                            val x = buffer[i] / 32768f * gain
                            // Soft saturation limits digital clipping; it is not an acoustic SPL limiter.
                            val y = (x / (1f + kotlin.math.abs(x))).coerceIn(-0.95f, 0.95f)
                            buffer[i] = (y * 32767f).toInt().toShort()
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } catch (_: IllegalStateException) {
                // Audio route may disappear; stop gracefully.
            } finally {
                active = false
                try { input.stop() } catch (_: Exception) {}
                input.release()
                output.release()
                if (recorder === input) recorder = null
                if (track === output) track = null
            }
        }.apply { name = "hearing-audio-demo"; start() }
    }

    @Synchronized fun stop() {
        active = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { track?.pause() } catch (_: Exception) {}
        worker?.join(300)
        worker = null
        recorder = null
        track = null
    }

    fun playTestTone(context: Context, frequencyHz: Int, level: Float) {
        Thread {
            val rate = 16000
            val frames = rate / 3
            val samples = ShortArray(frames)
            val amplitude = (level.coerceIn(0.05f, 0.30f) * 5000).toInt()
            for (i in samples.indices) {
                val envelope = minOf(1f, i / 160f, (frames - i) / 160f).coerceAtLeast(0f)
                samples[i] = (sin(2.0 * PI * frequencyHz * i / rate) * amplitude * envelope).toInt().toShort()
            }
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return@Thread
            val player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, samples.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            try { player.write(samples, 0, samples.size); player.play(); Thread.sleep(400) } catch (_: Exception) {} finally { player.release() }
        }.start()
    }
}
