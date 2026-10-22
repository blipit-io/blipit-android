package io.blipit.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {
    private class MemoryPrefs : SharedPreferences {
        val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                values.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    private val errors = ReplayPlan("errors", 0.0, 1.0, true)
    private val sessions = ReplayPlan("sessions", 0.25, 1.0, true)

    @Test
    fun nothingIsCapturedWithoutConsent() {
        assertEquals(ReplayPlan.OFF, ReplayGate.decide(false, false, 34, errors))
        assertEquals(ReplayPlan.OFF, ReplayGate.decide(false, false, 34, sessions))
        assertEquals(errors, ReplayGate.decide(true, false, 34, errors))
    }

    @Test
    fun childAppsOldDevicesAndMissingSettingsStayOff() {
        assertEquals(ReplayPlan.OFF, ReplayGate.decide(true, true, 34, sessions))
        assertEquals(ReplayPlan.OFF, ReplayGate.decide(true, false, 25, sessions))
        assertEquals(sessions, ReplayGate.decide(true, false, 26, sessions))
        assertEquals(ReplayPlan.OFF, ReplayGate.decide(true, false, 34, null))
    }

    @Test
    fun serverOffIsAKillSwitch() {
        val off = ReplayPlan.fromJson(mapOf("mode" to "off", "sessionSampleRate" to 0, "onErrorSampleRate" to 0, "labels" to true))
        assertEquals("off", off?.mode)
        assertEquals(ReplayPlan.OFF, ReplayGate.decide(true, false, 34, off))
    }

    @Test
    fun serverSettingsAreParsedStrictly() {
        assertEquals(errors, ReplayPlan.fromJson(mapOf("mode" to "errors", "sessionSampleRate" to 0.5, "onErrorSampleRate" to 1, "labels" to true)))
        assertEquals(ReplayPlan("sessions", 1.0, 1.0, false), ReplayPlan.fromJson(mapOf("mode" to "sessions", "sessionSampleRate" to 7, "onErrorSampleRate" to 1, "labels" to false)))
        assertNull(ReplayPlan.fromJson(mapOf("mode" to "everything")))
        assertNull(ReplayPlan.fromJson("errors"))
        assertNull(ReplayPlan.fromJson(null))
    }

    @Test
    fun settingsAreCachedOnTheDevice() {
        val prefs = MemoryPrefs()
        val store = ReplayStore(prefs)
        assertNull(store.server)
        assertEquals(0L, store.fetchedAt)
        store.save(sessions, now = 1_000L)
        val again = ReplayStore(prefs)
        assertEquals(sessions, again.server)
        assertEquals(1_000L, again.fetchedAt)
        store.save(ReplayPlan.OFF, now = 2_000L)
        assertEquals(ReplayPlan.OFF, ReplayStore(prefs).server)
        prefs.values["server"] = "{broken"
        assertNull(ReplayStore(prefs).server)
    }

    @Test
    fun consentDefaultsToFalseAndIsRemembered() {
        val prefs = MemoryPrefs()
        assertFalse(ReplayStore(prefs).consent)
        ReplayStore(prefs).consent = true
        assertTrue(ReplayStore(prefs).consent)
        ReplayStore(prefs).consent = false
        assertFalse(ReplayStore(prefs).consent)
    }

    @Test
    fun bugReportsCarryTheReplayId() {
        val id = "0123456789abcdef0123456789abcdef"
        val body = ReportPayload.feedback("t", "m", null, emptyMap(), emptyList(), 0, 0, 0, 0, "en", id)
        assertEquals(id, body["replay_id"])
        val bad = ReportPayload.feedback("t", "m", null, emptyMap(), emptyList(), 0, 0, 0, 0, "en", "../x")
        assertFalse(bad.containsKey("replay_id"))
    }

    @Test
    fun replayIsOffUntilConsentEvenAfterInit() {
        assertFalse(Blipit.hasReplayConsent())
        assertEquals("0.4.0", Blipit.VERSION)
    }
}
