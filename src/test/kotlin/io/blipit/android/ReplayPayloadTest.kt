package io.blipit.android

import io.blipit.nuggets.Json
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPayloadTest {
    private val frames = listOf(
        ReplayFrame(1_000_000L, byteArrayOf(1, 2), 352, 768),
        ReplayFrame(1_001_000L, byteArrayOf(3), 352, 768),
        ReplayFrame(1_002_000L, byteArrayOf(4), 352, 768),
    )

    @Test
    fun videoSizeIsEvenAndCapped() {
        assertArrayEquals(intArrayOf(352, 800), ReplayPayload.videoSize(1080, 2400, 360))
        assertArrayEquals(intArrayOf(320, 480), ReplayPayload.videoSize(320, 480, 360))
        assertArrayEquals(intArrayOf(0, 0), ReplayPayload.videoSize(0, 100, 360))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun segmentEventsMatchWhatTheDashboardReads() {
        val marks = listOf(ReplayPayload.screen(1_000_500L, "CheckoutActivity"), ReplayPayload.tap(1_001_200L, 12.7f, 40f), ReplayPayload.tap(10L, 1f, 1f))
        val events = ReplayPayload.events(3, frames, marks, 4096, "CheckoutActivity")
        assertEquals(listOf(4, 5, 5, 3), events.map { it["type"] })
        val video = (events[1]["data"] as Map<String, Any?>)
        assertEquals("video", video["tag"])
        val payload = video["payload"] as Map<String, Any?>
        assertEquals(3000L, payload["duration"])
        assertEquals(352, payload["width"])
        assertEquals(3, payload["segmentId"])
        val tap = events[3]["data"] as Map<String, Any?>
        assertEquals(mapOf("source" to 2, "type" to 7, "id" to 0, "x" to 12, "y" to 40), tap)
        val crumb = (events[2]["data"] as Map<String, Any?>)["payload"] as Map<String, Any?>
        assertEquals("navigation", crumb["category"])
        assertEquals(mapOf("to" to "CheckoutActivity"), crumb["data"])
    }

    @Test
    fun bodyCarriesBlipitKeysOnly() {
        val body = ReplayPayload.body(0, 999_000L, frames, emptyList(), byteArrayOf(0, 0, 0, 24), "Pixel 8", "Android 15", "Main")
        assertEquals(setOf("segment_id", "replay_start_timestamp", "timestamp", "platform", "device", "os", "screen", "events", "video"), body.keys)
        assertEquals(1_003_000L, body["timestamp"])
        assertEquals("AAAAGA==", body["video"])
        assertEquals("android", body["platform"])
    }

    @Test
    fun serverPauseStopsRecording() {
        assertEquals(true, ReplayPayload.accepted(202, """{"accepted":true}"""))
        assertEquals(false, ReplayPayload.accepted(202, """{"accepted":false,"reason":"replay_paused"}"""))
        assertEquals(false, ReplayPayload.accepted(202, """{"accepted":false,"reason":"replay_quota"}"""))
        assertEquals(true, ReplayPayload.accepted(202, """{"accepted":false,"reason":"replay_dropped"}"""))
        assertEquals(false, ReplayPayload.accepted(429, ""))
        assertNull(ReplayPayload.accepted(503, ""))
    }

    @Test
    fun crashBuffersSurviveOnDisk() {
        val file = File.createTempFile("blipit", ".bin")
        val marks = listOf(ReplayPayload.tap(1_000_100L, 1f, 2f))
        ReplayPayload.write(file, "0123456789abcdef0123456789abcdef", 4, 999_000L, "Main", frames, marks)
        val back = ReplayPayload.read(file)
        assertEquals("0123456789abcdef0123456789abcdef", back.replayId)
        assertEquals(4, back.n)
        assertEquals("Main", back.screen)
        assertEquals(3, back.frames.size)
        assertArrayEquals(byteArrayOf(1, 2), back.frames[0].jpeg)
        assertEquals(Json.encode(marks), Json.encode(back.events))
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(runCatching { ReplayPayload.read(file) }.isSuccess)
        assertTrue(file.delete())
    }
}
