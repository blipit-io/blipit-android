package io.blipit.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlipitTest {
    @Test
    fun securityContextDropsEmptyFields() {
        assertEquals(
            mapOf("kind" to "login_failed", "actor" to "ana@example.com", "ip" to "10.0.0.1", "target" to "/login"),
            Blipit.securityContext("login_failed", "ana@example.com", ip = "10.0.0.1", target = "/login"),
        )
    }

    @Test
    fun captureBeforeInitIsANoOp() {
        assertNull(Blipit.captureMessage("too early", Level.WARNING))
        assertNull(Blipit.captureException(IllegalStateException("too early")))
        assertTrue(Blipit.flush(10))
    }

    @Test
    fun defaultsMatchTheOldSdk() {
        val o = BlipitOptions()
        assertEquals("https://in.blipit.io", o.endpoint)
        assertEquals(0.0, o.tracesSampleRate, 0.0)
        assertTrue(o.anr && o.nativeCrashes && o.sessions)
        assertEquals(5000L, o.anrTimeoutMs)
        assertEquals("blipit-android", Blipit.SDK_NAME)
    }
}
