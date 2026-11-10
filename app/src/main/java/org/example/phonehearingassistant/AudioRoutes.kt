package org.example.phonehearingassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

object AudioRoutes {
    // Explicit headphone/hearing-aid classes only. BLE speakers and generic USB sound cards
    // must not pass the headphone guard simply because they are external devices.
    fun external(d: AudioDeviceInfo?): Boolean =
        d != null &&
            d.type in
                intArrayOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    23,
                    26,
                )

    fun label(d: AudioDeviceInfo?): String =
        if (d == null) "未路由"
        else
            "${d.productName} · ${when(d.type){8->"蓝牙媒体 A2DP"
7->"蓝牙通话 SCO · 单声道"
15->"手机麦克风"
26->"蓝牙 LE 耳机"
27->"蓝牙 LE 音箱（不支持）"
3,4->"有线耳机"
22->"USB 耳机"
23->"助听设备"
else->"音频设备 ${d.type}"}}"

    fun key(d: AudioDeviceInfo): String = "${d.productName}/${d.type}"

    fun output(am: AudioManager, bluetoothMic: Boolean = false): AudioDeviceInfo? {
        val ds = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { external(it) }
        return if (bluetoothMic) ds.firstOrNull { it.type == 7 || it.type == 26 }
        else ds.firstOrNull { it.type == 8 } ?: ds.firstOrNull { it.type != 7 }
    }

    /** Communication routes are mono; the caller must downmix stereo DSP frames first. */
    fun track(rate: Int, device: AudioDeviceInfo, communication: Boolean = false): AudioTrack {
        require(external(device)) { "仅支持明确的耳机或助听设备输出" }
        require(rate > 0) { "采样率无效" }
        require(device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO || communication) {
            "SCO 需要单声道通话路由"
        }
        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            require(rate == 8000 || rate == 16000) { "SCO 输出需要 8 kHz 或 16 kHz" }
        val mask =
            if (communication) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val channels = if (communication) 1 else 2
        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0) { "此设备不支持所需音频输出格式" }
        val size = max(minimum, rate / 10 * channels * 4)
        val output =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION
                            else AudioAttributes.USAGE_MEDIA
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        try {
            check(output.state == AudioTrack.STATE_INITIALIZED) { "播放设备初始化失败" }
            check(output.setPreferredDevice(device)) { "无法选择耳机输出" }
            return output
        } catch (t: Throwable) {
            try {
                output.release()
            } catch (_: Exception) {}
            throw t
        }
    }

    fun verifyTrack(track: AudioTrack, expected: AudioDeviceInfo) {
        val d = track.routedDevice
        check(external(d) && d?.id == expected.id) { "音频路由变化或未到达耳机，已停止" }
    }
}

/** A response staircase in digital level, deliberately not a clinical dB-HL audiometer. */
class ThresholdStaircase {
    var level = -60f
        private set

    private var lastDirection = 0
    private val reversals = mutableListOf<Float>()
    private var trials = 0
    var done = false
        private set

    var result = Float.NaN
        private set

    var noResponse = false
        private set

    fun answer(heard: Boolean) {
        if (done) return
        trials++
        val direction = if (heard) -1 else 1
        if (lastDirection != 0 && direction != lastDirection) reversals.add(level)
        if (!heard && level >= -18f) {
            done = true
            noResponse = true
            return
        }
        if (heard && level <= -78f) {
            done = true
            result = -78f
            return
        }
        if (reversals.size >= 4) {
            done = true
            result = reversals.takeLast(3).average().toFloat()
            return
        }
        if (trials >= 24) {
            done = true
            return
        }
        level = (level + if (heard) -10 else 5).coerceIn(-78f, -18f)
        lastDirection = direction
    }

    fun progress() = "第 ${trials+1} 次 · ${reversals.size}/4 个转折"
}

@Suppress("DEPRECATION", "MissingPermission")
class TonePlayer(private val context: Context) {
    @Volatile private var cancelled = false
    @Volatile private var cancelReason = "播放已停止，请重新测试此点"
    @Volatile private var track: AudioTrack? = null
    @Volatile private var generation = 0L
    @Volatile
    var playing = false
        private set

    /** Valid only after a successful onDone(null), and reset at the next trial's start. */
    @Volatile
    var lastRouteKey = ""
        private set

    @Volatile
    var lastSystemVolume = -1
        private set

    private val handler = Handler(Looper.getMainLooper())

    fun stop() {
        stopWithReason("播放已停止，请重新测试此点", generation)
    }

    private fun stopWithReason(reason: String, token: Long) {
        if (token != generation) return
        cancelReason = reason
        cancelled = true
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {}
    }

    /**
     * freq=0 is an actual silent catch trial with identical timing and route checks.
     * communication=true uses an independently established/verified mono SCO or LE call route. In
     * mono, channel is intentionally ignored: this is NOT an isolated-ear measurement. Existing
     * profile keys/levels can be required before emitting any nonzero sample.
     */
    @Synchronized
    fun play(
        freq: Int,
        db: Float,
        channel: Int,
        communication: Boolean = false,
        expectedRoute: String? = null,
        expectedVolume: Int? = null,
        onDone: (String?) -> Unit,
    ) {
        if (playing) {
            handler.post { onDone("正在播放，请稍候") }
            return
        }
        cancelled = false
        cancelReason = "播放已停止，请重新测试此点"
        playing = true
        lastRouteKey = ""
        lastSystemVolume = -1
        val token = ++generation
        Thread(
                {
                    var failure: String? = null
                    val am = context.getSystemService(AudioManager::class.java)
                    var focus: AudioFocusRequest? = null
                    var callback: AudioDeviceCallback? = null
                    var scoReceiver: BroadcastReceiver? = null
                    var output: AudioTrack? = null
                    var oldMode: Int? = null
                    var communicationRequested = false
                    var successfulRoute = ""
                    var successfulVolume = -1
                    try {
                        require(freq == 0 || freq in 80..8000) { "测试频率无效" }
                        require(db.isFinite()) { "测试音量无效" }
                        require(channel in 0..2) { "输出通道无效" }
                        check(!LiveState.running) { "请先停止助听" }
                        check(am.mode == AudioManager.MODE_NORMAL) { "请先结束通话或其他语音应用，再进行测听" }
                        val usage =
                            if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION
                            else AudioAttributes.USAGE_MEDIA
                        val request =
                            AudioFocusRequest.Builder(
                                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                                )
                                .setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(usage)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                                )
                                .setAcceptsDelayedFocusGain(false)
                                .setOnAudioFocusChangeListener(
                                    { change ->
                                        if (change < 0) stopWithReason("音频被其他应用打断，请重新测试此点", token)
                                    },
                                    handler,
                                )
                                .build()
                        focus = request
                        check(
                            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                        ) {
                            "未获得测听音频焦点，请暂停其他音频应用"
                        }
                        if (cancelled) error(cancelReason)
                        oldMode = am.mode
                        check(oldMode == AudioManager.MODE_NORMAL) { "音频通话状态已改变，请结束通话后再测试" }
                        am.mode =
                            if (communication) AudioManager.MODE_IN_COMMUNICATION
                            else AudioManager.MODE_NORMAL
                        val expectedMode =
                            if (communication) AudioManager.MODE_IN_COMMUNICATION
                            else AudioManager.MODE_NORMAL
                        val device: AudioDeviceInfo
                        if (communication) {
                            val deadline = SystemClock.elapsedRealtime() + 4000
                            communicationRequested = true
                            if (Build.VERSION.SDK_INT >= 31) {
                                var chosen =
                                    am.availableCommunicationDevices.firstOrNull {
                                        it.type == 7 || it.type == 26
                                    }
                                while (chosen == null && SystemClock.elapsedRealtime() < deadline) {
                                    if (cancelled) error(cancelReason)
                                    Thread.sleep(50)
                                    chosen =
                                        am.availableCommunicationDevices.firstOrNull {
                                            it.type == 7 || it.type == 26
                                        }
                                }
                                val requested = chosen ?: error("未发现蓝牙通话耳机，请在系统蓝牙设置中开启通话音频")
                                check(am.setCommunicationDevice(requested)) { "系统拒绝建立蓝牙通话测听路由" }
                                while (
                                    am.communicationDevice?.id != requested.id &&
                                        SystemClock.elapsedRealtime() < deadline
                                ) {
                                    if (cancelled) error(cancelReason)
                                    Thread.sleep(50)
                                }
                                check(am.communicationDevice?.id == requested.id) {
                                    "蓝牙通话测听路由建立超时，请重新连接耳机"
                                }
                                device = requested
                            } else {
                                val scoState = AtomicInteger(-1)
                                val receiver =
                                    object : BroadcastReceiver() {
                                        override fun onReceive(c: Context?, intent: Intent?) {
                                            if (
                                                intent?.action ==
                                                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED
                                            )
                                                scoState.set(
                                                    intent.getIntExtra(
                                                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                                                        -1,
                                                    )
                                                )
                                        }
                                    }
                                scoReceiver = receiver
                                val sticky =
                                    context.registerReceiver(
                                        receiver,
                                        IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                                    )
                                sticky?.let {
                                    scoState.set(
                                        it.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                                    )
                                }
                                am.startBluetoothSco()
                                while (
                                    scoState.get() != AudioManager.SCO_AUDIO_STATE_CONNECTED &&
                                        SystemClock.elapsedRealtime() < deadline
                                ) {
                                    if (cancelled) error(cancelReason)
                                    Thread.sleep(50)
                                }
                                check(scoState.get() == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                                    "蓝牙 SCO 连接超时，请开启耳机通话音频后再试"
                                }
                                am.isBluetoothScoOn = true
                                var connected = AudioRoutes.output(am, true)
                                while (
                                    connected == null && SystemClock.elapsedRealtime() < deadline
                                ) {
                                    if (cancelled) error(cancelReason)
                                    Thread.sleep(50)
                                    connected = AudioRoutes.output(am, true)
                                }
                                device = connected ?: error("SCO 已连接，但系统尚未提供耳机输出设备")
                            }
                        } else device = AudioRoutes.output(am) ?: error("请先连接骨传导耳机并开启媒体音频")
                        if (cancelled) error(cancelReason)
                        val volumeStream =
                            if (communication) AudioManager.STREAM_VOICE_CALL
                            else AudioManager.STREAM_MUSIC
                        val initialVol = am.getStreamVolume(volumeStream)
                        val maximum = am.getStreamMaxVolume(volumeStream)
                        val lowVolumeMax = max(1, maximum / 3)
                        val streamLabel = if (communication) "通话" else "媒体"
                        check(initialVol in 1..lowVolumeMax) {
                            "请将${streamLabel}音量调到较低且非静音（当前 $initialVol / $maximum，要求不高于 $lowVolumeMax），再重新播放"
                        }
                        val routeKey = AudioRoutes.key(device)
                        if (!expectedRoute.isNullOrBlank())
                            check(routeKey == expectedRoute) { "当前路由与档案不一致，请为此耳机及媒体/通话路由新建测听档案" }
                        if (expectedVolume != null && expectedVolume >= 0)
                            check(initialVol == expectedVolume) {
                                "当前${streamLabel}音量与档案不一致，请恢复到 $expectedVolume 档或新建档案"
                            }
                        callback =
                            object : AudioDeviceCallback() {
                                override fun onAudioDevicesRemoved(ds: Array<out AudioDeviceInfo>) {
                                    if (ds.any { it.id == device.id })
                                        stopWithReason("测试耳机已断开，请重新测试此点", token)
                                }
                            }
                        am.registerAudioDeviceCallback(callback, handler)
                        val rate = if (communication) 16000 else 48000
                        val channels = if (communication) 1 else 2
                        val blockSamples = rate / 100
                        val t = AudioRoutes.track(rate, device, communication)
                        output = t
                        track = t
                        if (cancelled) error(cancelReason)
                        t.play()
                        fun verify() {
                            if (cancelled) error(cancelReason)
                            check(!LiveState.running) { "助听已启动，测试音已停止" }
                            check(am.mode == expectedMode) { "通话状态改变，测试音已停止" }
                            check(am.getStreamVolume(volumeStream) == initialVol) {
                                "系统${streamLabel}音量改变，请重新建立本次测试"
                            }
                            AudioRoutes.verifyTrack(t, device)
                        }
                        // Only silence can be written before the actual route is confirmed.
                        val zeros = FloatArray(rate / 10 * channels)
                        var offset = 0
                        while (offset < zeros.size) {
                            if (cancelled) error(cancelReason)
                            val n =
                                t.write(
                                    zeros,
                                    offset,
                                    zeros.size - offset,
                                    AudioTrack.WRITE_BLOCKING,
                                )
                            check(n > 0) { "测试音输出初始化失败" }
                            offset += n
                        }
                        verify()
                        val amplitude =
                            if (freq == 0) 0f
                            else 10.0.pow(db.coerceIn(-78f, -18f) / 20.0).toFloat()
                        val samples = FloatArray(blockSamples * channels)
                        // Two 350 ms pulses, 150 ms apart, with 20 ms raised-cosine edges.
                        for (block in 0 until 85) {
                            verify()
                            samples.fill(0f)
                            if (freq != 0)
                                for (i in 0 until blockSamples) {
                                    val n = block * blockSamples + i
                                    val within = n % (rate / 2)
                                    val pulseSamples = rate * 35 / 100
                                    val edgeSamples = rate / 50
                                    val sounding = within < pulseSamples
                                    val envelope =
                                        if (sounding)
                                            min(
                                                    1.0,
                                                    min(
                                                        within / edgeSamples.toDouble(),
                                                        (pulseSamples - within) /
                                                            edgeSamples.toDouble(),
                                                    ),
                                                )
                                                .coerceAtLeast(0.0)
                                        else 0.0
                                    val v =
                                        (sin(2 * PI * freq * n / rate.toDouble()) *
                                                amplitude *
                                                (0.5 - 0.5 * cos(PI * envelope)))
                                            .toFloat()
                                    // The mono test amplitude is the actual mono dBFS level, not a
                                    // -6 dB one-sided downmix.
                                    if (communication) samples[i] = v
                                    else {
                                        if (channel == 0 || channel == 2) samples[2 * i] = v
                                        if (channel == 1 || channel == 2) samples[2 * i + 1] = v
                                    }
                                }
                            var off = 0
                            while (off < samples.size) {
                                verify()
                                val n =
                                    t.write(
                                        samples,
                                        off,
                                        samples.size - off,
                                        AudioTrack.WRITE_BLOCKING,
                                    )
                                check(n > 0) { "测试音输出失败" }
                                off += n
                            }
                        }
                        repeat(18) {
                            Thread.sleep(10)
                            verify()
                        }
                        successfulRoute = routeKey
                        successfulVolume = initialVol
                    } catch (e: Exception) {
                        failure = e.message ?: "测试音错误"
                    } finally {
                        callback?.let {
                            try {
                                am.unregisterAudioDeviceCallback(it)
                            } catch (_: Exception) {}
                        }
                        try {
                            output?.pause()
                            output?.flush()
                        } catch (_: Exception) {}
                        try {
                            output?.release()
                        } catch (_: Exception) {}
                        if (generation == token) track = null
                        // Tear down our request only after silencing/releasing the track.
                        if (communicationRequested)
                            try {
                                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                                else {
                                    am.stopBluetoothSco()
                                    am.isBluetoothScoOn = false
                                }
                            } catch (_: Exception) {}
                        scoReceiver?.let {
                            try {
                                context.unregisterReceiver(it)
                            } catch (_: Exception) {}
                        }
                        val ownedMode =
                            if (communication) AudioManager.MODE_IN_COMMUNICATION
                            else AudioManager.MODE_NORMAL
                        oldMode?.let { previous ->
                            if (am.mode == ownedMode)
                                try {
                                    am.mode = previous
                                } catch (_: Exception) {}
                        }
                        focus?.let {
                            try {
                                am.abandonAudioFocusRequest(it)
                            } catch (_: Exception) {}
                        }
                        handler.post {
                            if (generation == token) {
                                if (failure == null) {
                                    lastRouteKey = successfulRoute
                                    lastSystemVolume = successfulVolume
                                }
                                playing = false
                                onDone(failure)
                            }
                        }
                    }
                },
                "HearBridge-Tone",
            )
            .start()
    }
}
