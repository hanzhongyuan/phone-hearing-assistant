package org.example.phonehearingassistant

import android.content.Context
import android.os.Build
import java.util.UUID
import org.example.phonehearingassistant.dsp.DspConfig
import org.json.JSONArray
import org.json.JSONObject

class HearingProfile(val id: String = UUID.randomUUID().toString(), var name: String = "使用者 1") {
    val thresholds = Array(3) { FloatArray(8) { Float.NaN } }
    val comfortable = Array(3) { FloatArray(8) { Float.NaN } }
    val noResponse = Array(3) { BooleanArray(8) }
    var route = ""
    var systemVolume = -1
    var updated = ""
    var tuning = JSONObject()

    fun hasMeasurements() =
        thresholds.any { row -> row.any { it.isFinite() } } ||
            comfortable.any { row -> row.any { it.isFinite() } } ||
            noResponse.any { row -> row.any { it } }

    fun count() = thresholds.sumOf { row -> row.count { it.isFinite() } }

    fun json(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("route", route)
            .put("systemVolume", systemVolume)
            .put("updated", updated)
            .put("tuning", tuning)
            .put("unit", "dBFS; uncalibrated; output-channel response, NOT ear-specific dB HL")
            .put("frequencies", JSONArray(DspConfig.BAND_HZ.toList()))
            .put(
                "thresholds",
                JSONArray(
                    thresholds.map { row ->
                        JSONArray(row.map { if (it.isFinite()) it else JSONObject.NULL })
                    }
                ),
            )
            .put(
                "comfortable",
                JSONArray(
                    comfortable.map { row ->
                        JSONArray(row.map { if (it.isFinite()) it else JSONObject.NULL })
                    }
                ),
            )
            .put("noResponse", JSONArray(noResponse.map { JSONArray(it.toList()) }))

    companion object {
        fun parse(o: JSONObject): HearingProfile {
            val p = HearingProfile(o.getString("id"), o.optString("name", "使用者"))
            p.route = o.optString("route")
            p.systemVolume = o.optInt("systemVolume", -1)
            p.updated = o.optString("updated")
            p.tuning = o.optJSONObject("tuning") ?: JSONObject()
            for (e in 0..2) for (b in 0..7) {
                p.thresholds[e][b] =
                    o.optJSONArray("thresholds")
                        ?.optJSONArray(e)
                        ?.optDouble(b, Double.NaN)
                        ?.toFloat() ?: Float.NaN
                p.comfortable[e][b] =
                    o.optJSONArray("comfortable")
                        ?.optJSONArray(e)
                        ?.optDouble(b, Double.NaN)
                        ?.toFloat() ?: Float.NaN
                p.noResponse[e][b] =
                    o.optJSONArray("noResponse")?.optJSONArray(e)?.optBoolean(b) ?: false
            }
            return p
        }
    }
}

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("hearbridge", Context.MODE_PRIVATE)
    val profiles = mutableListOf<HearingProfile>()
    var selected = prefs.getString("selected", "")!!
    var mode = prefs.getInt("mode", 1)
    var mic = prefs.getString("mic", "phone")!!
    var denoiser = prefs.getString("denoiser", "gtcrn")!!
    var asr = prefs.getString("asr", "zh")!!
    var mapping = prefs.getInt("mapping", 0)
    var targetLow = prefs.getFloat("low", 300f)
    var targetHigh = prefs.getFloat("high", 3000f)
    var master = prefs.getFloat("master", 0f)
    var ceiling = prefs.getFloat("ceiling", -24f)
    var ratio = prefs.getFloat("ratio", 2f)
    var feedback = prefs.getBoolean("feedback", true)
    var compression = prefs.getBoolean("compression", true)
    var autoSpeak = prefs.getBoolean("autoSpeak", true)
    var balance = prefs.getFloat("balance", 0f)
    var rating = prefs.getString("rating", "")!!
    var consent = prefs.getBoolean("consent", false)
    val manual = Array(2) { e -> FloatArray(8) { b -> prefs.getFloat("gain_${e}_$b", 0f) } }

    init {
        try {
            val a = JSONArray(prefs.getString("profiles", "[]"))
            for (i in 0 until a.length()) profiles.add(HearingProfile.parse(a.getJSONObject(i)))
        } catch (_: Exception) {}
        if (profiles.isEmpty()) profiles.add(HearingProfile())
        if (profiles.none { it.id == selected }) selected = profiles[0].id
    }

    fun profile() = profiles.first { it.id == selected }

    fun config(): DspConfig {
        val c = DspConfig()
        c.mode = mode
        c.mappingType = mapping
        c.masterGainDb = master.coerceIn(-12f, 12f)
        c.ceilingDb = ceiling.coerceIn(-36f, -18f)
        c.compressionRatio = ratio.coerceIn(1f, 4f)
        c.compressionEnabled = compression
        c.feedbackSuppression = feedback
        c.noiseReduction = denoiser == "wiener"
        c.targetLowHz = floatArrayOf(targetLow, targetLow)
        c.targetHighHz = floatArrayOf(targetHigh, targetHigh)
        // Relative response fitting: never treat dBFS as dB HL or guess inaudible bands.
        val p = profile()
        for (e in 0..1) {
            val valid = p.thresholds[e].filter { it.isFinite() }
            val base = valid.minOrNull() ?: -60f
            for (b in 0..7) {
                val t = p.thresholds[e][b]
                c.gainDb[e][b] =
                    ((if (t.isFinite()) ((t - base) * 0.4f).coerceIn(0f, 12f) else 0f) +
                            manual[e][b] +
                            (if (e == 0) -balance else balance))
                        .coerceIn(-12f, 18f)
            }
        }
        return c
    }

    private fun captureTuning(): JSONObject =
        JSONObject()
            .put("mode", mode)
            .put("mic", mic)
            .put("denoiser", denoiser)
            .put("asr", asr)
            .put("mapping", mapping)
            .put("low", targetLow)
            .put("high", targetHigh)
            .put("master", master)
            .put("ceiling", ceiling)
            .put("ratio", ratio)
            .put("feedback", feedback)
            .put("compression", compression)
            .put("autoSpeak", autoSpeak)
            .put("balance", balance)
            .put("rating", rating)
            .put("manual", JSONArray(manual.map { JSONArray(it.toList()) }))

    private fun restoreTuning(o: JSONObject) {
        mode = o.optInt("mode", 1)
        mic = o.optString("mic", "phone")
        denoiser = o.optString("denoiser", "gtcrn")
        asr = o.optString("asr", "zh")
        mapping = o.optInt("mapping", 0)
        targetLow = o.optDouble("low", 300.0).toFloat()
        targetHigh = o.optDouble("high", 3000.0).toFloat()
        master = o.optDouble("master", 0.0).toFloat()
        ceiling = o.optDouble("ceiling", -24.0).toFloat()
        ratio = o.optDouble("ratio", 2.0).toFloat()
        feedback = o.optBoolean("feedback", true)
        compression = o.optBoolean("compression", true)
        autoSpeak = o.optBoolean("autoSpeak", true)
        balance = o.optDouble("balance", 0.0).toFloat()
        rating = o.optString("rating", "")
        for (e in 0..1) for (b in 0..7) manual[e][b] =
            o.optJSONArray("manual")?.optJSONArray(e)?.optDouble(b, 0.0)?.toFloat() ?: 0f
    }

    fun selectProfile(id: String) {
        require(profiles.any { it.id == id })
        profile().tuning = captureTuning()
        selected = id
        restoreTuning(profile().tuning)
        save()
    }

    fun save() {
        profile().tuning = captureTuning()
        val e =
            prefs
                .edit()
                .putFloat("balance", balance)
                .putString("rating", rating)
                .putString("selected", selected)
                .putString("profiles", JSONArray(profiles.map { it.json() }).toString())
                .putInt("mode", mode)
                .putString("mic", mic)
                .putString("denoiser", denoiser)
                .putString("asr", asr)
                .putInt("mapping", mapping)
                .putFloat("low", targetLow)
                .putFloat("high", targetHigh)
                .putFloat("master", master)
                .putFloat("ceiling", ceiling)
                .putFloat("ratio", ratio)
                .putBoolean("feedback", feedback)
                .putBoolean("compression", compression)
                .putBoolean("autoSpeak", autoSpeak)
                .putBoolean("consent", consent)
        for (a in 0..1) for (b in 0..7) e.putFloat("gain_${a}_$b", manual[a][b])
        e.apply()
    }

    fun export(transcript: String = ""): String =
        JSONObject()
            .put("app", "HearBridge 1.0.0 tech preview")
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("profile", profile().json())
            .put("mode", mode)
            .put("denoiser", denoiser)
            .put("microphone", mic)
            .put("mapping", mapping)
            .put("targetLowHz", targetLow)
            .put("targetHighHz", targetHigh)
            .put("masterGainDb", master)
            .put("ceilingDbFS", ceiling)
            .put("compressionRatio", ratio)
            .put("compression", compression)
            .put("feedbackSuppression", feedback)
            .put("manualGainDb", JSONArray(manual.map { JSONArray(it.toList()) }))
            .put("balanceDb", balance)
            .put("trialRating", rating)
            .put("transcript", transcript)
            .put(
                "warning",
                "Not clinical audiometry. Digital ceiling does not establish acoustic safety. Bone conduction has cross-hearing.",
            )
            .toString(2)
}

object LiveState {
    @Volatile var running = false
    @Volatile var status = "尚未启动"
    @Volatile var route = "未连接"
    @Volatile var input = "未启动"
    @Volatile var partial = ""
    @Volatile var transcript = ""
    @Volatile var rms = -100f
    @Volatile var peak = -100f
    @Volatile var processingMs = 0f
    @Volatile var underruns = 0
    @Volatile var replayText: String? = null
    @Volatile var speaking = false
    @Volatile var skippedNarrations = 0

    @Synchronized
    fun append(text: String) {
        transcript = (transcript + if (transcript.isEmpty()) text else "\n$text").takeLast(12000)
    }
}
