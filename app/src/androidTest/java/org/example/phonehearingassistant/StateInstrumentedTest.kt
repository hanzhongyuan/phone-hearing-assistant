package org.example.phonehearingassistant

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.example.phonehearingassistant.dsp.DspConfig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated state/serialization tests: never touch the user's real hearbridge preferences. */
@RunWith(AndroidJUnit4::class)
class StateInstrumentedTest {
    private lateinit var base: Context
    private lateinit var isolated: Context
    private val preferenceNames = LinkedHashSet<String>()

    @Before
    fun isolatePreferences() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "state_test_${UUID.randomUUID()}_"
        isolated =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val testName = prefix + name
                    preferenceNames.add(testName)
                    return base.getSharedPreferences(testName, mode)
                }
            }
    }

    @After
    fun removeOnlyTestPreferences() {
        for (name in preferenceNames) {
            // commit drains earlier apply writes before the file is deleted.
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            base.deleteSharedPreferences(name)
        }
        preferenceNames.clear()
    }

    @Test
    fun profileSwitchAndReloadKeepIndependentHearingAndTuning() {
        val settings = AppSettings(isolated)
        val first = settings.profile()
        first.name = "测试 A"
        first.thresholds[0][2] = -47f
        first.route = "test-route-A"
        first.systemVolume = 3
        settings.mode = 2
        settings.mic = "bluetooth"
        settings.denoiser = "dpdfnet"
        settings.asr = "bilingual"
        settings.mapping = 1
        settings.targetLow = 500f
        settings.targetHigh = 1800f
        settings.master = -4f
        settings.ceiling = -30f
        settings.ratio = 3f
        settings.feedback = false
        settings.compression = true
        settings.autoSpeak = false
        settings.balance = -2f
        settings.rating = "A: 舒适"
        settings.manual[0][0] = 6f
        settings.manual[1][7] = -4f
        settings.save()

        val second = HearingProfile(name = "测试 B")
        settings.profiles.add(second)
        settings.selectProfile(second.id)
        assertEquals("A new person must start with default mode", 1, settings.mode)
        assertEquals("phone", settings.mic)
        assertEquals("gtcrn", settings.denoiser)
        assertTrue(settings.manual.all { row -> row.all { it == 0f } })
        assertFalse(second.hasMeasurements())
        settings.mode = 3
        settings.denoiser = "wiener"
        settings.asr = "zh"
        settings.targetLow = 700f
        settings.targetHigh = 3200f
        settings.master = 2f
        settings.ceiling = -24f
        settings.ratio = 1.5f
        settings.feedback = true
        settings.compression = false
        settings.autoSpeak = true
        settings.balance = 4f
        settings.rating = "B: 待复测"
        settings.manual[0][0] = -3f
        settings.manual[1][7] = 5f
        second.thresholds[1][3] = -56f
        second.noResponse[0][7] = true
        second.route = "test-route-B"
        second.systemVolume = 5
        settings.save()

        val reloaded = AppSettings(isolated)
        assertEquals(second.id, reloaded.selected)
        assertEquals(3, reloaded.mode)
        assertEquals("wiener", reloaded.denoiser)
        assertEquals(5f, reloaded.manual[1][7], 0f)
        reloaded.selectProfile(first.id)
        assertEquals(2, reloaded.mode)
        assertEquals("bluetooth", reloaded.mic)
        assertEquals("dpdfnet", reloaded.denoiser)
        assertEquals("bilingual", reloaded.asr)
        assertEquals(1, reloaded.mapping)
        assertEquals(500f, reloaded.targetLow, 0f)
        assertEquals(1800f, reloaded.targetHigh, 0f)
        assertEquals(-4f, reloaded.master, 0f)
        assertEquals(-30f, reloaded.ceiling, 0f)
        assertEquals(3f, reloaded.ratio, 0f)
        assertFalse(reloaded.feedback)
        assertTrue(reloaded.compression)
        assertFalse(reloaded.autoSpeak)
        assertEquals(-2f, reloaded.balance, 0f)
        assertEquals("A: 舒适", reloaded.rating)
        assertEquals(6f, reloaded.manual[0][0], 0f)
        assertEquals(-4f, reloaded.manual[1][7], 0f)
        assertEquals(-47f, reloaded.profile().thresholds[0][2], 0f)
        assertTrue(reloaded.profile().thresholds[1][3].isNaN())
        assertEquals("test-route-A", reloaded.profile().route)
        assertEquals(3, reloaded.profile().systemVolume)
        assertFalse(reloaded.profile().noResponse[0][7])

        val persistedAgain = AppSettings(isolated)
        assertEquals(first.id, persistedAgain.selected)
        assertEquals(2, persistedAgain.profiles.size)
        persistedAgain.selectProfile(second.id)
        assertEquals(3, persistedAgain.mode)
        assertEquals("phone", persistedAgain.mic)
        assertEquals("zh", persistedAgain.asr)
        assertEquals(700f, persistedAgain.targetLow, 0f)
        assertEquals(3200f, persistedAgain.targetHigh, 0f)
        assertEquals(2f, persistedAgain.master, 0f)
        assertEquals(-24f, persistedAgain.ceiling, 0f)
        assertEquals(1.5f, persistedAgain.ratio, 0f)
        assertTrue(persistedAgain.feedback)
        assertFalse(persistedAgain.compression)
        assertTrue(persistedAgain.autoSpeak)
        assertEquals(4f, persistedAgain.balance, 0f)
        assertEquals("B: 待复测", persistedAgain.rating)
        assertEquals(-3f, persistedAgain.manual[0][0], 0f)
        assertEquals(5f, persistedAgain.manual[1][7], 0f)
        assertEquals(-56f, persistedAgain.profile().thresholds[1][3], 0f)
        assertTrue(persistedAgain.profile().thresholds[0][2].isNaN())
        assertTrue(persistedAgain.profile().noResponse[0][7])
        assertEquals("test-route-B", persistedAgain.profile().route)
        assertEquals(5, persistedAgain.profile().systemVolume)
    }

    @Test
    fun jsonNullPreservesUnmeasuredValuesAndNoResponse() {
        val original = HearingProfile(id = "round-trip", name = "序列化测试")
        original.thresholds[0][0] = -61.5f
        original.thresholds[1][1] = Float.POSITIVE_INFINITY
        original.comfortable[2][4] = -32f
        original.comfortable[0][5] = Float.NEGATIVE_INFINITY
        original.noResponse[2][7] = true
        original.route = "test-route"
        original.systemVolume = 4
        val json = original.json()
        assertTrue(json.getJSONArray("thresholds").getJSONArray(0).isNull(1))
        assertTrue(json.getJSONArray("thresholds").getJSONArray(1).isNull(1))
        assertTrue(json.getJSONArray("comfortable").getJSONArray(0).isNull(5))
        val serialized = json.toString()
        assertFalse("JSON must not contain non-standard NaN literals", serialized.contains("NaN"))
        assertFalse(serialized.contains("Infinity"))
        val restored = HearingProfile.parse(JSONObject(serialized))
        assertEquals("round-trip", restored.id)
        assertEquals(-61.5f, restored.thresholds[0][0], 0f)
        assertTrue(restored.thresholds[0][1].isNaN())
        assertTrue(restored.thresholds[1][1].isNaN())
        assertEquals(-32f, restored.comfortable[2][4], 0f)
        assertTrue(restored.comfortable[0][5].isNaN())
        assertTrue(restored.noResponse[2][7])
        assertEquals(1, restored.count())
        assertTrue(restored.hasMeasurements())
        assertEquals("test-route", restored.route)
        assertEquals(4, restored.systemVolume)
    }

    @Test
    fun noResponseAndComfortAloneStillLockMeasurementContext() {
        val none = HearingProfile()
        assertFalse(none.hasMeasurements())
        assertEquals(0, none.count())
        none.noResponse[1][6] = true
        assertEquals(0, none.count())
        assertTrue(
            "A failed response is still a measured route/volume context",
            none.hasMeasurements(),
        )
        val restored = HearingProfile.parse(none.json())
        assertTrue(restored.hasMeasurements())
        assertTrue(restored.noResponse[1][6])
        val comfortOnly = HearingProfile()
        comfortOnly.comfortable[0][3] = -36f
        assertTrue(comfortOnly.hasMeasurements())
        assertEquals(0, comfortOnly.count())
    }

    @Test
    fun unmeasuredAndNoResponseBandsReceiveNoAutomaticGain() {
        val settings = AppSettings(isolated)
        val profile = settings.profile()
        profile.thresholds[0][0] = -70f
        profile.thresholds[0][1] = -40f
        profile.noResponse[0][2] = true
        profile.thresholds[1][5] = -45f
        val config = settings.config()
        assertEquals(0f, config.gainDb[0][0], 0f)
        assertTrue(config.gainDb[0][1] > 0f)
        for (band in 2..7) assertEquals(
            "Unknown left bands must stay neutral",
            0f,
            config.gainDb[0][band],
            0f,
        )
        for (band in 0..7) assertEquals(
            "A lone known threshold cannot establish relative loss",
            0f,
            config.gainDb[1][band],
            0f,
        )
        assertTrue(config.gainDb.all { row -> row.all { it.isFinite() && it in -12f..18f } })
    }

    @Test
    fun relativeFittingBoundsFiniteGainsAndRejectsInvalidThresholds() {
        val config = DspConfig()
        config.fitRelativeThresholds(
            floatArrayOf(
                -90f,
                -70f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                -20f,
                8f,
                Float.NEGATIVE_INFINITY,
                -100f,
            ),
            floatArrayOf(Float.NaN),
        )
        assertTrue(config.gainDb.all { row -> row.all { it.isFinite() && it in 0f..18f } })
        for (band in listOf(2, 3, 5, 6)) assertEquals(0f, config.gainDb[0][band], 0f)
        assertTrue(config.gainDb[1].all { it == 0f })
        assertEquals(18f, config.gainDb[0][4], 0f)
        assertEquals(0f, config.gainDb[0][7], 0f)
    }

    @Test
    fun staircaseTerminatesForStableResponsesAndStaysWithinBounds() {
        val staircase = ThresholdStaircase()
        var answers = 0
        while (!staircase.done && answers < 30) {
            assertTrue(staircase.level in -78f..-18f)
            staircase.answer(staircase.level >= -45f)
            answers++
        }
        assertTrue("A stable listener must produce a bounded-length result", staircase.done)
        assertTrue(answers <= 24)
        assertTrue(staircase.result.isFinite())
        assertTrue(staircase.result in -60f..-40f)
        assertFalse(staircase.noResponse)
        val result = staircase.result
        val level = staircase.level
        repeat(5) { staircase.answer(false) }
        assertEquals(result, staircase.result, 0f)
        assertEquals(level, staircase.level, 0f)
    }

    @Test
    fun staircaseNeverEscalatesBeyondCeilingWhenNothingIsHeard() {
        val staircase = ThresholdStaircase()
        repeat(30) {
            assertTrue(staircase.level in -78f..-18f)
            staircase.answer(false)
        }
        assertTrue(staircase.done)
        assertTrue(staircase.noResponse)
        assertTrue(
            "No response must not become a fabricated hearing threshold",
            staircase.result.isNaN(),
        )
        assertEquals(-18f, staircase.level, 0f)
        staircase.answer(true)
        assertTrue(staircase.result.isNaN())
        assertTrue(staircase.noResponse)
    }

    @Test
    fun staircaseStopsAtTestFloorWhenEveryToneIsHeard() {
        val staircase = ThresholdStaircase()
        repeat(30) {
            assertTrue(staircase.level in -78f..-18f)
            staircase.answer(true)
        }
        assertTrue(staircase.done)
        assertFalse(staircase.noResponse)
        assertEquals(-78f, staircase.result, 0f)
        assertEquals(-78f, staircase.level, 0f)
    }
}
