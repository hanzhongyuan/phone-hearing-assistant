package org.example.phonehearingassistant

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import org.example.phonehearingassistant.dsp.HearingDsp
import org.example.phonehearingassistant.ml.SpeechModels

class HearingService : Service() {
    private val alive = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    private lateinit var am: AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private var selectedId = -1
    @Volatile private var scoConnected = false

    companion object {
        private val sessionOwned = AtomicBoolean(false)
    }

    private val scoReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                scoConnected =
                    i?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ==
                        AudioManager.SCO_AUDIO_STATE_CONNECTED
            }
        }
    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(ds: Array<out AudioDeviceInfo>) {
                if (ds.any { it.id == selectedId }) halt("耳机断开，已停止")
            }
        }
    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                halt("输出设备改变，已停止")
            }
        }

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
        am.registerAudioDeviceCallback(deviceCallback, main)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                RECEIVER_EXPORTED,
            )
        else
            registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                RECEIVER_NOT_EXPORTED,
            )
        else registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            halt("已停止")
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        if (!sessionOwned.compareAndSet(false, true)) {
            LiveState.status = "上一会话仍在释放资源，请稍后再试"
            if (worker == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        alive.set(true)
        val channel = NotificationChannel("hearing", "助听运行", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, HearingService::class.java).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val open =
            PendingIntent.getActivity(
                this,
                2,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val note =
            Notification.Builder(this, "hearing")
                .setSmallIcon(R.drawable.ic_hearing)
                .setContentTitle("听桥正在使用麦克风")
                .setContentText("离线处理 · 点此返回 · 随时停止")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "立即停止", stop).build())
                .build()
        try {
            if (Build.VERSION.SDK_INT >= 30)
                startForeground(41, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(41, note)
        } catch (e: Exception) {
            alive.set(false)
            sessionOwned.set(false)
            LiveState.status = "无法启动麦克风服务：${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        LiveState.running = true
        LiveState.status = "正在加载离线模型…"
        LiveState.partial = ""
        LiveState.replayText = null
        worker = Thread({ runAudio() }, "HearBridge-Audio").also { it.start() }
        return START_NOT_STICKY
    }

    private fun halt(message: String) {
        LiveState.status = message
        alive.set(false)
        try {
            track?.pause()
            track?.flush()
            record?.stop()
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun runAudio() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val settings = AppSettings(this)
        val models = SpeechModels(this)
        val synthesizer = Executors.newSingleThreadExecutor()
        val synthBusy = AtomicBoolean(false)
        val spoken = ArrayBlockingQueue<FloatArray>(1)
        val oldMode = am.mode
        var usedCommunication = false
        try {
            val attr =
                AudioAttributes.Builder()
                    .setUsage(
                        if (settings.mic == "bluetooth") AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            focus =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attr)
                    .setOnAudioFocusChangeListener({ if (it < 0) halt("其他音频或通话打断，已停止") }, main)
                    .build()
            check(am.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "未获得音频焦点"
            }
            if (settings.mic == "bluetooth") {
                usedCommunication = true
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val d =
                        am.availableCommunicationDevices.firstOrNull {
                            it.type == 7 || it.type == 26
                        } ?: error("未发现蓝牙通话麦克风，请确认 Shokz 已开启通话音频")
                    check(am.setCommunicationDevice(d)) { "系统拒绝蓝牙通话路由" }
                } else {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                var waits = 0
                while (
                    alive.get() &&
                        waits++ < 40 &&
                        (AudioRoutes.output(am, true) == null ||
                            (Build.VERSION.SDK_INT < 31 && !scoConnected))
                ) Thread.sleep(100)
                if (Build.VERSION.SDK_INT < 31) check(scoConnected) { "蓝牙通话链路连接超时" }
            } else {
                am.mode = AudioManager.MODE_NORMAL
            }
            check(alive.get()) { "已停止" }
            val device =
                AudioRoutes.output(am, settings.mic == "bluetooth") ?: error("请连接耳机并打开对应媒体/通话音频")
            selectedId = device.id
            val volumeStream =
                if (settings.mic == "bluetooth") AudioManager.STREAM_VOICE_CALL
                else AudioManager.STREAM_MUSIC
            val initialVolume = am.getStreamVolume(volumeStream)
            check(initialVolume in 1..max(1, am.getStreamMaxVolume(volumeStream) / 3)) {
                "请先把对应媒体/通话系统音量设为非零且不超过最大档位的1/3"
            }
            val dspConfig = settings.config()
            // A media-route self-check cannot be transferred to the SCO route without repeating it.
            val profile = settings.profile()
            if (profile.hasMeasurements())
                check(
                    profile.route == AudioRoutes.key(device) &&
                        profile.systemVolume == initialVolume
                ) {
                    "当前耳机路由或系统音量与测听档案不同；请重测或新建档案"
                }
            val comfort = profile.comfortable.flatMap { it.toList() }.filter { it.isFinite() }
            if (comfort.isNotEmpty())
                dspConfig.ceilingDb = min(dspConfig.ceilingDb, comfort.min() - 3f)
            val dsp = HearingDsp(dspConfig)
            models.loadDenoiser(
                if (settings.denoiser in listOf("off", "wiener")) "off" else settings.denoiser
            )
            if (settings.mode == 3) {
                LiveState.status = "正在加载离线识别和语音模型…"
                models.loadAsr(settings.asr)
                models.loadTts()
            }
            check(alive.get()) { "已停止" }
            val output = AudioRoutes.track(16000, device, settings.mic == "bluetooth")
            track = output
            output.play()
            val warm = FloatArray(if (settings.mic == "bluetooth") 1600 else 3200)
            check(output.write(warm, 0, warm.size, AudioTrack.WRITE_BLOCKING) > 0)
            AudioRoutes.verifyTrack(output, device)
            val inputDevice =
                am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                    if (settings.mic == "bluetooth") it.type == 7 || it.type == 26
                    else it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                } ?: error("未发现所选麦克风")
            val minBuffer =
                AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                )
            val input =
                AudioRecord.Builder()
                    .setAudioSource(
                        if (settings.mic == "bluetooth")
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        else MediaRecorder.AudioSource.VOICE_RECOGNITION
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minBuffer, 8192))
                    .build()
            record = input
            check(input.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            check(input.setPreferredDevice(inputDevice)) { "无法选择所需麦克风" }
            input.startRecording()
            wake =
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HearBridge:audio")
                    .also { it.acquire(30 * 60 * 1000L) }
            LiveState.route = AudioRoutes.label(output.routedDevice)
            LiveState.status = "运行中 · ${if(settings.mode==3)"字幕识别" else "实时助听"}"
            var pendingFlush = false
            var holdUntil = 0L
            var ttsAudio: FloatArray? = null
            var ttsOffset = 0
            var denoiseFill = 0
            val denoiseFrame = FloatArray(256)
            val raw = FloatArray(256)
            var runFrames = 0
            fun requestSpeech(text: String) {
                if (text.isBlank()) return
                if (spoken.remainingCapacity() == 0 || !synthBusy.compareAndSet(false, true)) {
                    LiveState.skippedNarrations++
                    return
                }
                LiveState.status = "正在合成重述…"
                synthesizer.submit {
                    try {
                        val audio = SpeechModels.resample16k(models.synthesize(text.take(300)))
                        if (alive.get() && !spoken.offer(audio)) LiveState.skippedNarrations++
                    } catch (e: Throwable) {
                        if (alive.get()) LiveState.status = "语音生成失败：${e.message?.take(100)}"
                    } finally {
                        synthBusy.set(false)
                    }
                }
            }
            fun write(stereo: FloatArray) {
                val samples =
                    if (settings.mic == "bluetooth")
                        FloatArray(stereo.size / 2) { i ->
                            (stereo[2 * i] + stereo[2 * i + 1]) * 0.5f
                        }
                    else stereo
                var offset = 0
                while (alive.get() && offset < samples.size) {
                    AudioRoutes.verifyTrack(output, device)
                    check(am.getStreamVolume(volumeStream) == initialVolume) {
                        "系统音量发生变化，已停止；请确认舒适度后重新启动"
                    }
                    val n =
                        output.write(
                            samples,
                            offset,
                            samples.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                    check(n > 0) { "耳机播放失败 $n" }
                    offset += n
                }
            }
            while (alive.get()) {
                var filled = 0
                while (alive.get() && filled < 256) {
                    val n = input.read(raw, filled, 256 - filled, AudioRecord.READ_BLOCKING)
                    check(n > 0) { "录音被中断 $n" }
                    filled += n
                }
                if (!alive.get()) break
                check(input.routedDevice?.id == inputDevice.id) { "麦克风路由已改变，已停止" }
                AudioRoutes.verifyTrack(output, device)
                check(am.getStreamVolume(volumeStream) == initialVolume) { "系统音量发生变化，已停止" }
                val begin = System.nanoTime()
                if (runFrames++ % 30 == 0) {
                    LiveState.input = AudioRoutes.label(input.routedDevice)
                    LiveState.rms =
                        (20 * log10(sqrt(raw.sumOf { it.toDouble() * it } / raw.size) + 1e-9))
                            .toFloat()
                    LiveState.underruns = output.underrunCount
                }
                if (settings.mode == 3) {
                    LiveState.replayText?.let {
                        LiveState.replayText = null
                        requestSpeech(it)
                    }
                    if (ttsAudio == null && !pendingFlush) {
                        spoken.poll()?.let {
                            ttsAudio = it
                            ttsOffset = 0
                            dsp.reset()
                            models.resetAsr()
                            LiveState.partial = ""
                            LiveState.speaking = true
                            LiveState.status = "正在重述 · 暂停收听防止回授"
                        }
                    }
                    val speech = ttsAudio
                    if (pendingFlush) {
                        write(dsp.process(FloatArray(256)))
                        pendingFlush = false
                    } else if (speech != null) {
                        val frame = FloatArray(256)
                        val n = min(256, speech.size - ttsOffset)
                        if (n > 0) System.arraycopy(speech, ttsOffset, frame, 0, n)
                        ttsOffset += n
                        write(dsp.process(frame))
                        if (ttsOffset >= speech.size) {
                            pendingFlush = true
                            ttsAudio = null
                            holdUntil = SystemClock.elapsedRealtime() + 700
                            LiveState.speaking = false
                            models.resetDenoiser()
                        }
                    } else if (SystemClock.elapsedRealtime() >= holdUntil) {
                        LiveState.status = if (synthBusy.get()) "字幕识别中 · 重述正在合成" else "运行中 · 字幕识别"
                        val enhanced = models.denoise(raw)
                        if (enhanced.isNotEmpty())
                            models.accept(enhanced)?.let { r ->
                                LiveState.partial = r.text
                                if (r.isFinal) {
                                    LiveState.append(r.text)
                                    LiveState.partial = ""
                                    if (settings.autoSpeak) requestSpeech(r.text)
                                }
                            }
                        write(FloatArray(512))
                    } else write(FloatArray(512))
                } else {
                    val enhanced = models.denoise(raw)
                    for (v in enhanced) {
                        denoiseFrame[denoiseFill++] = v
                        if (denoiseFill == 256) {
                            write(dsp.process(denoiseFrame))
                            denoiseFill = 0
                        }
                    }
                }
                LiveState.peak = dsp.outputPeakDb
                LiveState.processingMs = (System.nanoTime() - begin) / 1_000_000f
                if (runFrames >= 16000 / 256 * 20 * 60) {
                    LiveState.status = "已到20分钟试用时限，请休息并检查舒适度"
                    break
                }
            }
        } catch (t: Throwable) {
            if (alive.get())
                LiveState.status = "已停止：${t.message?.take(180) ?: t.javaClass.simpleName}"
        } finally {
            alive.set(false)
            try {
                record?.stop()
            } catch (_: Exception) {}
            record?.release()
            record = null
            try {
                track?.pause()
                track?.flush()
                track?.release()
            } catch (_: Exception) {}
            track = null
            synthesizer.shutdownNow()
            models.close()
            if (usedCommunication) {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
            }
            am.mode = oldMode
            focus?.let { am.abandonAudioFocusRequest(it) }
            if (wake?.isHeld == true) wake?.release()
            LiveState.running = false
            LiveState.speaking = false
            main.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                sessionOwned.set(false)
            }
        }
    }

    override fun onDestroy() {
        alive.set(false)
        try {
            track?.pause()
            track?.flush()
            record?.stop()
        } catch (_: Exception) {}
        am.unregisterAudioDeviceCallback(deviceCallback)
        try {
            unregisterReceiver(scoReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(noisy)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
