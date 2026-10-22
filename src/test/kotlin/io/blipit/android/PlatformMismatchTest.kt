package io.blipit.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformMismatchTest {
    private val refused = """{"error":"This project is for iOS. Create a separate Android project in Blipit and use its key.","reason":"platform_mismatch","project_platform":"ios","sent_platform":"android"}"""

    @Test
    fun onlyAPlatformMismatchBlocksSending() {
        PlatformMismatch.reset()
        assertFalse(PlatformMismatch.check(409, """{"error":"this report already has a recording at that position"}"""))
        assertFalse(PlatformMismatch.check(400, refused))
        assertFalse(PlatformMismatch.blocked)
        assertTrue(PlatformMismatch.check(409, refused))
        assertTrue(PlatformMismatch.blocked)
        assertTrue(PlatformMismatch.check(409, refused))
        PlatformMismatch.reset()
    }

    @Test
    fun aRefusedReplayPausesAndStopsLaterUploads() {
        PlatformMismatch.reset()
        assertEquals(false, ReplayPayload.accepted(409, refused))
        assertTrue(PlatformMismatch.blocked)
        PlatformMismatch.reset()
    }

    @Test
    fun theReportClientRefusesToSendOnceBlocked() {
        PlatformMismatch.reset()
        PlatformMismatch.check(409, refused)
        val failed = runCatching { ReportClient("http://127.0.0.1:9", "pk").send(mapOf("message" to "x")) }.exceptionOrNull()
        assertEquals(409, (failed as ReportException).status)
        PlatformMismatch.reset()
    }
}
