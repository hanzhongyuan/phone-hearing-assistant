package org.example.phonehearingassistant

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import org.example.phonehearingassistant.dsp.DspConfig

class MainActivity : Activity() {
    private val bg = Color.rgb(16, 26, 35)
    private val panel = Color.rgb(26, 41, 53)
    private val accent = Color.rgb(91, 224, 193)
    private val muted = Color.rgb(165, 184, 196)
    private val white = Color.rgb(237, 245, 247)
    private lateinit var settings: AppSettings
    private lateinit var tone: TonePlayer
    private lateinit var body: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var banner: TextView
    private var metrics: TextView? = null
    private var transcriptView: TextView? = null
    private var startButton: Button? = null
    private var tab = 0
    private val handler = Handler(Looper.getMainLooper())
    private var ear = 0
    private var band = 0
    private var staircase = ThresholdStaircase()
    private var canAnswer = false
    private var catchTrial = false
    private var falseAlarms = 0
    private var comfortLevel = -45f
    private var comfortPlayed = false
    private var testMessage = "点击播放后，再回答是否听到。"
    private var exportBody = ""
    private var toneToken = 0
    private val tick =
        object : Runnable {
            override fun run() {
                if (::banner.isInitialized)
                    banner.text = if (tone.playing) "正在播放测试音 · 随时按停止" else LiveState.status
                metrics?.text =
                    "输入  ${LiveState.input}\n输出  ${if(LiveState.running)LiveState.route else routeSummary()}\n输入 RMS ${format(LiveState.rms)} dBFS   输出峰值 ${format(LiveState.peak)} dBFS\n单次处理/写入 ${format(LiveState.processingMs)} ms · 欠载 ${LiveState.underruns}\n此计时不含蓝牙传输，不是端到端延迟。"
                transcriptView?.text =
                    ((if (LiveState.skippedNarrations > 0)
                            "重述繁忙，${LiveState.skippedNarrations} 句仅显示字幕。\n\n"
                        else "") +
                            LiveState.transcript +
                            if (LiveState.partial.isNotEmpty()) "\n\n${LiveState.partial} …"
                            else "")
                        .ifEmpty { "等待讲话…\n离线字幕可能出错，请核对人名、数字和重要信息。" }
                startButton?.text = if (LiveState.running) "停止助听" else "开始助听"
                handler.postDelayed(this, 350)
            }
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        settings = AppSettings(this)
        tone = TonePlayer(this)
        tab = state?.getInt("tab") ?: 0
        render()
        if (!settings.consent) consent()
        handler.post(tick)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("tab", tab)
        super.onSaveInstanceState(out)
    }

    override fun onPause() {
        super.onPause()
        if (tone.playing) {
            tone.stop()
            canAnswer = false
            comfortPlayed = false
            testMessage = "测试中断，请重新播放"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized && tab == 1) render()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tone.stop()
        super.onDestroy()
    }

    private fun format(v: Float) = if (v.isFinite()) String.format(Locale.US, "%.1f", v) else "—"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun shape(color: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(18).toFloat()
        }

    private fun text(value: String, size: Float = 15f, color: Int = white) =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun LinearLayout.label(s: String, size: Float = 15f, color: Int = white): TextView =
        text(s, size, color).also {
            addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

    private fun button(s: String, primary: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            text = s
            textSize = 15f
            isAllCaps = false
            setTextColor(if (primary) bg else white)
            background = shape(if (primary) accent else Color.rgb(39, 59, 72))
            minHeight = dp(50)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { action() }
        }

    private fun LinearLayout.action(s: String, primary: Boolean = false, f: () -> Unit): Button =
        button(s, primary, f).also {
            addView(
                it,
                LinearLayout.LayoutParams(-1, dp(52)).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                },
            )
        }

    private fun card(title: String, subtitle: String = "", block: LinearLayout.() -> Unit) {
        val l =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = shape(panel)
            }
        l.label(title, 20f)
        if (subtitle.isNotEmpty()) l.label(subtitle, 13f, muted)
        l.block()
        body.addView(l, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    private fun edit(hint: String, value: String = "") =
        EditText(this).apply {
            setHint(hint)
            setText(value)
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 16f
            setSingleLine(true)
        }

    private fun LinearLayout.choices(
        title: String,
        labels: List<String>,
        selected: Int,
        onChange: (Int) -> Unit,
    ) {
        label(title, 14f, muted)
        val s = Spinner(this@MainActivity)
        s.adapter =
            ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        s.setSelection(selected.coerceIn(0, labels.lastIndex))
        var ready = false
        s.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: AdapterView<*>?) {}

                override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                    (v as? TextView)?.setTextColor(white)
                    if (ready && i != selected) onChange(i)
                    ready = true
                }
            }
        addView(s, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun LinearLayout.slider(
        title: String,
        value: Float,
        lo: Int,
        hi: Int,
        unit: String = "dB",
        change: (Float) -> Unit,
    ) {
        val label = label("$title  ${format(value)} $unit", 14f, muted)
        val bar =
            SeekBar(this@MainActivity).apply {
                max = hi - lo
                progress = value.roundToInt() - lo
            }
        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {
                    change(((s?.progress ?: 0) + lo).toFloat())
                }

                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                    label.text = "$title  ${p+lo} $unit"
                }
            }
        )
        addView(bar, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun LinearLayout.toggle(title: String, checked: Boolean, change: (Boolean) -> Unit) {
        addView(
            Switch(this@MainActivity).apply {
                text = title
                setTextColor(white)
                isChecked = checked
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, v -> change(v) }
            }
        )
    }

    private fun stop() {
        toneToken++
        tone.stop()
        canAnswer = false
        comfortPlayed = false
        startService(Intent(this, HearingService::class.java).setAction("STOP"))
        LiveState.status = "正在停止…"
    }

    private fun changed(action: () -> Unit) {
        if (LiveState.running) stop()
        action()
        settings.save()
    }

    private fun routeSummary(): String =
        try {
            AudioRoutes.label(
                AudioRoutes.output(
                    getSystemService(AudioManager::class.java),
                    settings.mic == "bluetooth",
                )
            )
        } catch (_: Exception) {
            "需要蓝牙权限"
        }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun consent() {
        AlertDialog.Builder(this)
            .setTitle("先低音量，随时停止")
            .setMessage(
                "这是技术试用版，尚未通过你的 vivo + Shokz 真机与临床验证。\n\n自测单位为 dBFS，不是 dB HL。骨传导两侧可能串音；数字限幅不等于实际声压安全。请从很低的系统音量开始，在安静环境下短时试听；不舒适、耳鸣或头晕立即停止。突然听力下降请就医。\n\n音频和字幕只在手机处理，不联网、不录音。模式3重述期间暂停识别，可能漏掉讲话。"
            )
            .setPositiveButton("了解，进入试用") { _, _ ->
                settings.consent = true
                settings.save()
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun permissions(): Boolean {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) required.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing =
            required
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .toMutableList()
        val p = getSharedPreferences("permission_prompts", MODE_PRIVATE)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                !p.getBoolean("notificationAsked", false) &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            p.edit().putBoolean("notificationAsked", true).apply()
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 30)
            return false
        }
        return true
    }

    private fun launch() {
        if (!settings.consent) {
            consent()
            return
        }
        if (tone.playing) {
            toast("请先停止测试音")
            return
        }
        if (LiveState.running) {
            stop()
            return
        }
        if (!permissions()) return
        settings.save()
        startForegroundService(Intent(this, HearingService::class.java))
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, p, grants)
        if (code == 30) toast("权限已更新，请点击开始；通知可拒绝，麦克风和蓝牙连接为必需。")
    }

    private fun render() {
        metrics = null
        transcriptView = null
        startButton = null
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(bg)
                setPadding(dp(16), dp(12), dp(16), dp(8))
            }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 35)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val x = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(16), x.top + dp(10), dp(16), x.bottom + dp(8))
                insets
            }
        val head =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        head.addView(text("听桥", 27f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(text("HEARBRIDGE  /  01", 11f, accent))
        root.addView(head)
        val state =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        banner = text(LiveState.status, 12f, accent)
        state.addView(banner, LinearLayout.LayoutParams(0, -2, 1f))
        state.addView(button("■ 停止") { stop() }, LinearLayout.LayoutParams(dp(90), dp(44)))
        root.addView(state)
        val scroll = ScrollView(this)
        body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(14), 0, dp(10))
            }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (tab) {
            0 -> home()
            1 -> hearingTest()
            2 -> tuning()
            3 -> captions()
            else -> records()
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("助听", "测听", "微调", "字幕", "档案").forEachIndexed { i, s ->
            nav.addView(
                button(s, i == tab) {
                    if (tone.playing) {
                        tone.stop()
                        canAnswer = false
                    }
                    tab = i
                    render()
                },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(3) },
            )
        }
        root.addView(nav)
    }

    private fun home() {
        body.label("让声音，清晰抵达。", 24f)
        body.label("vivo X100 Ultra × Shokz · 全离线技术试用", 13f, muted)
        card("聆听模式", "先完成测听，再在安静环境中选择清晰而舒适的设置。") {
            val modes =
                listOf(
                    "01  个体补偿" to "按相对响应拟合频带增益，动态压缩强弱声音。",
                    "02  频谱重映射" to "把频率压缩到目标区间；音色会改变。",
                    "03  字幕与重述" to "离线识别 → 字幕 → 合成语音 → 频率映射。",
                )
            modes.forEachIndexed { i, p ->
                action(
                    (if (settings.mode == i + 1) "● " else "○ ") + p.first,
                    settings.mode == i + 1,
                ) {
                    changed { settings.mode = i + 1 }
                    render()
                }
                label(p.second, 13f, muted)
            }
            startButton = action(if (LiveState.running) "停止助听" else "开始助听", true) { launch() }
        }
        card("输入与增强", "切换设置后会停止当前会话，请重新开始。") {
            choices(
                "麦克风",
                listOf("手机麦克风 · 建议靠近说话者", "Shokz 麦克风 · 蓝牙通话模式"),
                if (settings.mic == "phone") 0 else 1,
            ) {
                changed { settings.mic = if (it == 0) "phone" else "bluetooth" }
                render()
            }
            val kinds = listOf("gtcrn", "dpdfnet", "wiener", "off")
            choices(
                "降噪算法",
                listOf("GTCRN · 轻量神经增强", "DPDFNet2 · 深度滤波", "Wiener · 传统低算力", "关闭降噪 · 对照试听"),
                kinds.indexOf(settings.denoiser),
            ) {
                changed { settings.denoiser = kinds[it] }
            }
            label("Shokz 通话路由通常降低带宽，且不一定保留立体声。自测以媒体播放为准；切换路由后不能沿用未验证的拟合。", 12f, muted)
        }
        card("实时状态") {
            metrics = label("等待启动…", 13f, muted)
            action("打开蓝牙设置") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }

    private fun testContext(): Boolean {
        if (LiveState.running) {
            toast("先停止助听，再开始测听")
            return false
        }
        if (!permissions()) return false
        if (settings.mic == "bluetooth") {
            val p = settings.profile()
            if (
                p.hasMeasurements() &&
                    p.systemVolume !=
                        getSystemService(AudioManager::class.java)
                            .getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            ) {
                toast("通话音量已变化，请新建档案重测")
                return false
            }
            return true
        }
        val am = getSystemService(AudioManager::class.java)
        val d =
            AudioRoutes.output(am)
                ?: run {
                    toast("请连接 Shokz 媒体音频")
                    return false
                }
        val p = settings.profile()
        val key = AudioRoutes.key(d)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (p.hasMeasurements() && (p.route != key || p.systemVolume != vol)) {
            toast("耳机或系统音量已变化，请新建档案后重新测听")
            return false
        }
        p.route = key
        p.systemVolume = vol
        settings.save()
        return true
    }

    private fun playThreshold() {
        if (!testContext()) return
        canAnswer = false
        comfortPlayed = false
        catchTrial = Random().nextInt(5) == 0
        val token = ++toneToken
        testMessage = "测试音播放中…"
        render()
        tone.play(
            if (catchTrial) 0 else DspConfig.BAND_HZ[band].toInt(),
            staircase.level,
            ear,
            communication = settings.mic == "bluetooth",
            expectedRoute = settings.profile().takeIf { it.hasMeasurements() }?.route,
            expectedVolume = settings.profile().takeIf { it.hasMeasurements() }?.systemVolume,
        ) { err ->
            if (isDestroyed || token != toneToken) return@play
            if (err != null) {
                testMessage = err
                canAnswer = false
            } else {
                settings.profile().route = tone.lastRouteKey
                settings.profile().systemVolume = tone.lastSystemVolume
                settings.save()
                canAnswer = true
                testMessage = "这一轮听到声音了吗？"
            }
            render()
        }
    }

    private fun answer(heard: Boolean) {
        if (!canAnswer || tone.playing) return
        if (!testContext()) {
            canAnswer = false
            render()
            return
        }
        canAnswer = false
        // Silent catch trials assess accidental responses without changing the staircase.
        if (catchTrial) {
            if (heard) falseAlarms++
            testMessage = if (heard) "这一轮是静音检查。请确认环境安静，避免把环境声音当测试音；继续复测。" else "静音检查通过，继续测试。"
            catchTrial = false
            render()
            return
        }
        staircase.answer(heard)
        if (staircase.done && falseAlarms >= 2) {
            testMessage = "静音试次出现多次误报，本次结果不保存。请调整环境后重新测试。"
            render()
            return
        }
        if (staircase.done) {
            val p = settings.profile()
            p.thresholds[ear][band] = staircase.result
            p.noResponse[ear][band] = staircase.noResponse
            p.updated = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            settings.save()
            testMessage =
                if (staircase.result.isFinite()) "已保存相对响应 ${format(staircase.result)} dBFS"
                else if (staircase.noResponse) "本次上限仍未听到；不继续提高强度，也不自动增加此频段增益。" else "结果不稳定，请休息后重测。"
        } else testMessage = "已记录。点击播放下一轮（${format(staircase.level)} dBFS）。"
        render()
    }

    private fun resetPoint() {
        falseAlarms = 0
        toneToken++
        tone.stop()
        staircase = ThresholdStaircase()
        canAnswer = false
        comfortPlayed = false
        testMessage = "从 -60 dBFS 开始，请保持系统音量不变。"
    }

    private fun hearingTest() {
        body.label("先单侧，再双侧联调", 24f)
        body.label("125–6000 Hz · 8 个频点 · 单位 dBFS", 13f, accent)
        card("测试准备", "音量保持很低且固定（不超过最大音量的1/3）；安静环境、固定佩戴位置。单侧输出可能经骨传导被两耳同时听到。") {
            label("当前档案：${settings.profile().name} · 已记录 ${settings.profile().count()}/24 点", 14f)
            if (settings.mic == "bluetooth")
                label("蓝牙通话模式为单声道：两侧选择均测同一混合输出，不代表左右独立。双侧联调建议切回手机麦克风/媒体路由。", 13f, accent)
            choices("输出通道", listOf("左侧输出", "右侧输出", "双侧输出 · 独立联调"), ear) {
                ear = it
                resetPoint()
                render()
            }
            choices("测试频率", DspConfig.BAND_HZ.map { "${it.toInt()} Hz" }, band) {
                band = it
                resetPoint()
                render()
            }
            label("${DspConfig.BAND_HZ[band].toInt()} Hz", 36f, accent)
            label("${format(staircase.level)} dBFS · ${staircase.progress()}", 14f, muted)
            label(testMessage, 15f)
            action(if (staircase.done) "重新测试此频点" else "播放本轮测试音", true) {
                if (staircase.done) resetPoint()
                playThreshold()
            }
            val row = LinearLayout(this@MainActivity)
            listOf("听到了" to true, "没听到" to false).forEach { p ->
                row.addView(
                    button(p.first) { answer(p.second) }
                        .apply { isEnabled = canAnswer && !tone.playing },
                    LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) },
                )
            }
            addView(row)
            action("下一个频点 / 输出侧") {
                if (band < 7) band++
                else {
                    band = 0
                    ear = (ear + 1) % 3
                }
                resetPoint()
                render()
            }
        }
        card("记录舒适电平", "这是主观舒适参考，不是耐受极限。勿追求最大响度；播放后只有舒适才记录。") {
            slider("参考测试音", comfortLevel, -72, -18, "dBFS") {
                comfortLevel = it
                comfortPlayed = false
                toneToken++
                tone.stop()
            }
            action("试听舒适参考") {
                if (testContext()) {
                    canAnswer = false
                    comfortPlayed = false
                    val token = ++toneToken
                    tone.play(
                        DspConfig.BAND_HZ[band].toInt(),
                        comfortLevel,
                        ear,
                        communication = settings.mic == "bluetooth",
                        expectedRoute = settings.profile().takeIf { it.hasMeasurements() }?.route,
                        expectedVolume =
                            settings.profile().takeIf { it.hasMeasurements() }?.systemVolume,
                    ) { err ->
                        if (isDestroyed || token != toneToken) return@play
                        comfortPlayed = err == null
                        if (comfortPlayed) {
                            settings.profile().route = tone.lastRouteKey
                            settings.profile().systemVolume = tone.lastSystemVolume
                            settings.save()
                        }
                        testMessage = err ?: "若舒适，可记录该参考电平。"
                        render()
                    }
                }
            }
            action("舒适，保存参考电平") {
                if (!comfortPlayed) toast("请先试听当前参考电平")
                else if (testContext()) {
                    settings.profile().comfortable[ear][band] = comfortLevel
                    settings.save()
                    comfortPlayed = false
                    toast("已保存；助听限幅将更保守地参考此电平")
                    render()
                }
            }
        }
        card("相对响应曲线", "绿=左侧输出，橙=右侧输出，蓝=双侧。没有数值表示未测或未听到，不推断为正常。") {
            addView(
                AudiogramView(this@MainActivity, settings.profile()),
                LinearLayout.LayoutParams(-1, dp(225)),
            )
            action("根据已测曲线建议目标频段") {
                val p = settings.profile()
                val rows =
                    if (p.thresholds[2].count { it.isFinite() } >= 3) listOf(p.thresholds[2])
                    else p.thresholds.take(2)
                val candidates =
                    (1..5)
                        .map { i -> i to rows.flatMap { r -> (i - 1..i + 1).map { r[it] } } }
                        .filter { it.second.all { v -> v.isFinite() } }
                val best = candidates.minByOrNull { it.second.average() }
                if (best == null) toast("请至少完成连续三个频点的测试")
                else {
                    changed {
                        settings.targetLow = max(125f, DspConfig.BAND_HZ[best.first - 1].toFloat())
                        settings.targetHigh =
                            min(6000f, DspConfig.BAND_HZ[best.first + 1].toFloat())
                    }
                    toast(
                        "建议范围 ${settings.targetLow.toInt()}–${settings.targetHigh.toInt()} Hz；还需双侧语音试听确认"
                    )
                }
            }
        }
    }

    private fun tuning() {
        body.label("小步调整，反复比较", 24f)
        body.label("设置改变后重新启动助听，避免突然增益变化。", 13f, muted)
        card("输出与动态范围") {
            slider("总增益", settings.master, -12, 12) { changed { settings.master = it } }
            slider("数字峰值上限", settings.ceiling, -36, -18, "dBFS") {
                changed { settings.ceiling = it }
            }
            toggle("多频带动态压缩 WDRC", settings.compression) { changed { settings.compression = it } }
            slider("压缩比", settings.ratio, 1, 4, ": 1") { changed { settings.ratio = it } }
            toggle("持续窄带啸叫检测与抑制", settings.feedback) { changed { settings.feedback = it } }
            label("限幅不代表耳内声压已校准。反馈保护为啸叫衰减，并非完整声学反馈抵消。", 12f, muted)
        }
        card("频率映射 · 模式 2 / 3", "目标范围越窄，声音失真通常越大。只能通过语音试听判断收益。") {
            choices("映射算法", listOf("全频段压缩到目标范围", "保留低频 + 高频压缩搬移"), settings.mapping) {
                changed { settings.mapping = it }
            }
            slider("目标下限", settings.targetLow, 125, 2000, "Hz") {
                changed { settings.targetLow = min(it, settings.targetHigh - 250) }
            }
            slider("目标上限", settings.targetHigh, 750, 6000, "Hz") {
                changed { settings.targetHigh = max(it, settings.targetLow + 250) }
            }
        }
        card("双侧联调", "单侧测试完成后，两侧一起听。调平衡不会提高总峰值上限。") {
            slider("平衡（负=偏左，正=偏右）", settings.balance, -6, 6) { changed { settings.balance = it } }
            val note = edit("记录清晰度、舒适度、左右平衡", settings.rating)
            addView(note)
            action("保存本次试听评价") {
                settings.rating = note.text.toString()
                settings.save()
                toast("已保存")
            }
        }
        for (e in 0..1) card(
            if (e == 0) "左输出 · 频带微调" else "右输出 · 频带微调",
            "基于自测的实验性拟合 + 手动增益；不是 NAL-NL2 或 DSL 临床处方。",
        ) {
            for (b in 0..7) slider(
                "${DspConfig.BAND_HZ[b].toInt()} Hz",
                settings.manual[e][b],
                -12,
                12,
            ) {
                changed { settings.manual[e][b] = it }
            }
        }
        card("对照预设") {
            action("安静对话 · GTCRN + 补偿") {
                changed {
                    settings.mode = 1
                    settings.denoiser = "gtcrn"
                    settings.ratio = 2f
                    settings.master = 0f
                }
                render()
            }
            action("噪声对话 · DPDFNet + 补偿") {
                changed {
                    settings.mode = 1
                    settings.denoiser = "dpdfnet"
                    settings.ratio = 2f
                    settings.master = 0f
                }
                render()
            }
            action("恢复微调默认值") {
                changed {
                    settings.manual.forEach { it.fill(0f) }
                    settings.master = 0f
                    settings.ceiling = -24f
                    settings.ratio = 2f
                    settings.balance = 0f
                }
                render()
            }
        }
    }

    private fun captions() {
        body.label("看见对话，听见重述", 24f)
        card("离线字幕", "模式3需先启动。原声不直通；语音按句合成，存在等待。重述时暂停识别，避免循环转写。") {
            choices(
                "识别模型",
                listOf("Zipformer CTC · 中文轻量", "Zipformer Transducer · 中英双语"),
                if (settings.asr == "zh") 0 else 1,
            ) {
                changed { settings.asr = if (it == 0) "zh" else "bilingual" }
            }
            toggle("每句结束自动重述", settings.autoSpeak) { changed { settings.autoSpeak = it } }
            action("启动模式3", true) {
                if (LiveState.running) {
                    toast("请先停止当前模式，再启动")
                } else {
                    settings.mode = 3
                    settings.save()
                    launch()
                }
            }
            transcriptView = label("等待讲话…", 24f)
            action("清空本地字幕") {
                LiveState.transcript = ""
                LiveState.partial = ""
            }
        }
        card("确认文字后重述", "字幕可能出错。你可以输入或修正一句话再播放。合成语音也经过频率映射和限幅。") {
            val input = edit("输入中文或英文短句")
            input.setSingleLine(false)
            input.minLines = 2
            addView(input)
            action("重述输入文字", true) {
                if (!LiveState.running || settings.mode != 3) toast("请先启动模式3")
                else if (input.text.isBlank()) toast("请输入文字")
                else {
                    LiveState.replayText = input.text.toString().take(300)
                    toast("已提交重述请求")
                }
            }
        }
    }

    private fun records() {
        body.label("每个人，一份自己的设置", 24f)
        card("个体档案", "数据保存在本机。更换耳机、音量或佩戴方式后，应建立新档案重测。") {
            choices(
                "当前档案",
                settings.profiles.map { it.name },
                settings.profiles.indexOf(settings.profile()),
            ) {
                changed { settings.selectProfile(settings.profiles[it].id) }
                resetPoint()
                render()
            }
            val name = edit("使用者代号 / 耳机与佩戴说明")
            addView(name)
            action("新建档案") {
                if (name.text.isBlank()) toast("请输入使用者代号")
                else {
                    changed {
                        val p = HearingProfile(name = name.text.toString().take(60))
                        settings.profiles.add(p)
                        settings.selectProfile(p.id)
                    }
                    resetPoint()
                    render()
                }
            }
            label(
                "已测 ${settings.profile().count()}/24 点\n${settings.profile().route}\n系统音量级：${settings.profile().systemVolume}\n记录时间：${settings.profile().updated}",
                13f,
                muted,
            )
            action("导出档案、参数和字幕 JSON") {
                exportBody = settings.export(LiveState.transcript)
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(
                            Intent.EXTRA_TITLE,
                            "HearBridge-${SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(Date())}.json",
                        ),
                    70,
                )
            }
        }
        card("算法与研究", "所有音频模型随安装包提供；应用没有联网权限，不上传声音。") {
            label("已集成：GTCRN / DPDFNet2；Zipformer 中文与中英识别；Melo 中英语音合成；8频带WDRC、频率压缩、限幅、窄带反馈保护。", 14f)
            label(
                "v1.0.0 技术试用版\nAndroid 8+ · sherpa-onnx 2.0.1\nCPU 端侧处理。尚未在你的 vivo X100 Ultra 和具体 Shokz 型号上验证端到端效果。",
                13f,
                muted,
            )
            action("查看研究与源码") {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse(
                            "https://github.com/hanzhongyuan/phone-hearing-assistant"
                        ),
                    )
                )
            }
            action("查看模型许可与来源") {
                val s =
                    try {
                        assets.open("models/manifest.json").bufferedReader().readText()
                    } catch (_: Exception) {
                        "模型来源与许可见项目 docs/MODELS.md；sherpa-onnx Apache-2.0，Melo MIT。"
                    }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("开源模型")
                    .setMessage(s.take(20000))
                    .setPositiveButton("关闭", null)
                    .show()
            }
            label("使用中保持通知栏运行；vivo 省电策略可能中断后台录音。如出现中断，再在系统设置中允许该应用后台运行。", 12f, muted)
        }
    }

    @Deprecated("Android Activity result compatibility")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 70 && result == RESULT_OK)
            data?.data?.let { uri ->
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(exportBody)
                    }
                    toast("已导出")
                } catch (e: Exception) {
                    toast("导出失败：${e.message}")
                }
            }
    }
}

class AudiogramView(context: Context, private val profile: HearingProfile) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val margin = 60f
        val w = width - margin - 25
        val h = height - 60f
        paint.textSize = 24f
        paint.strokeWidth = 1f
        for (db in -80..-20 step 20) {
            val y = 20 + (-20 - db) / 60f * h
            paint.color = Color.rgb(65, 83, 96)
            c.drawLine(margin, y, width - 15f, y, paint)
            paint.color = Color.LTGRAY
            c.drawText("$db", 5f, y + 5, paint)
        }
        for (i in 0..7) {
            val x = margin + i * w / 7
            paint.color = Color.LTGRAY
            paint.textSize = 21f
            c.drawText(
                listOf("125", "250", "500", "1k", "2k", "3k", "4k", "6k")[i],
                x - 15,
                height - 10f,
                paint,
            )
        }
        for (e in 0..2) {
            paint.color =
                intArrayOf(
                    Color.rgb(91, 224, 193),
                    Color.rgb(255, 186, 111),
                    Color.rgb(113, 175, 255),
                )[e]
            paint.strokeWidth = 4f
            var oldX = 0f
            var oldY = 0f
            var prev = false
            for (i in 0..7) {
                val t = profile.thresholds[e][i]
                if (!t.isFinite()) {
                    prev = false
                    continue
                }
                val x = margin + i * w / 7
                val y = 20 + (-20 - t) / 60f * h
                if (prev) c.drawLine(oldX, oldY, x, y, paint)
                c.drawCircle(x, y, 6f, paint)
                oldX = x
                oldY = y
                prev = true
            }
        }
    }
}
