package io.blipit.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTest {
    private var now = 0L
    private fun clock(deadline: Long = 20_000) = StartupClock(deadline) { now }

    @Test
    fun firesOnceAfterDeadlineWithoutReady() {
        val c = clock()
        c.foreground()
        now = 19_999
        assertFalse(c.check())
        now = 20_000
        assertTrue(c.check())
        now = 60_000
        assertFalse(c.check())
        assertTrue(c.stalled)
    }

    @Test
    fun doesNotFireWhenReadyInTime() {
        val c = clock()
        c.foreground()
        now = 5_000
        assertEquals(5_000L, c.ready())
        now = 60_000
        assertFalse(c.check())
        assertFalse(c.armed())
        assertNull(c.ready())
    }

    @Test
    fun clockPausesInBackground() {
        val c = clock()
        c.foreground()
        now = 10_000
        c.background()
        now = 500_000
        assertEquals(10_000L, c.elapsed())
        assertFalse(c.check())
        c.foreground()
        now = 509_999
        assertFalse(c.check())
        assertEquals(1L, c.remaining())
        now = 510_000
        assertTrue(c.check())
    }

    @Test
    fun notStartedUntilForeground() {
        val c = clock()
        now = 100_000
        assertFalse(c.check())
        assertEquals(0L, c.elapsed())
    }

    @Test
    fun disabledWithZero() {
        val c = clock(0)
        c.foreground()
        now = 1_000_000
        assertFalse(c.armed())
        assertFalse(c.check())
    }

    @Test
    fun lateReadyIsMeasuredWithoutSecondEvent() {
        val c = clock()
        c.foreground()
        now = 20_000
        assertTrue(c.check())
        now = 31_000
        assertEquals(31_000L, c.ready())
        assertTrue(c.stalled)
        assertFalse(c.check())
    }

    @Test
    fun messageNamesTheDeadline() {
        assertEquals("App did not become ready within 20 s", Startup.message(20_000))
        assertEquals("App did not become ready within 7.5 s", Startup.message(7_500))
    }

    @Test
    fun defaultDeadlineIsTwentySeconds() {
        assertEquals(20_000L, BlipitOptions().startupDeadlineMs)
    }
}
