package io.blipit.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckTest {
    private val limits = StuckLimits(20_000, 60_000, setOf("low_memory"), 200)

    @Test
    fun flowIsStuckOnlyAfterItsTimeOnScreen() {
        val watch = FlowWatch()
        watch.foreground(true, 0)
        watch.start("sign-in", 0)
        watch.step("sign-in", "google-picker")
        assertTrue(watch.due(59_999, limits).isEmpty())
        assertEquals(listOf(StuckFlow("sign-in", "google-picker", 60_000)), watch.due(60_000, limits))
        assertTrue("a stuck flow is reported once", watch.due(120_000, limits).isEmpty())
    }

    @Test
    fun timeInTheBackgroundDoesNotCount() {
        val watch = FlowWatch()
        watch.start(FlowWatch.STARTUP, 0)
        assertTrue("a process started in the background waits for a screen", watch.due(100_000, limits).isEmpty())
        watch.foreground(true, 100_000)
        watch.foreground(false, 110_000)
        watch.foreground(true, 500_000)
        assertTrue(watch.due(509_999, limits).isEmpty())
        assertEquals(20_000L, watch.due(510_000, limits).single().waitedMs)
    }

    @Test
    fun endedFlowsAndMissingLimitsStayQuiet() {
        val watch = FlowWatch()
        watch.foreground(true, 0)
        watch.start("pay", 0)
        assertTrue("no server limits yet, nothing is decided", watch.due(1_000_000, null).isEmpty())
        assertTrue(watch.end("pay"))
        assertFalse(watch.end("pay"))
        assertFalse(watch.step("pay", "sheet"))
        assertTrue(watch.due(1_000_000, limits).isEmpty())
    }

    @Test
    fun limitsComeFromTheServer() {
        val parsed = StuckLimits.fromJson(Json.parse("""{"startup_ms":20000,"flow_ms":60000,"exit_reasons":["low_memory"],"exit_importance":200}"""))
        assertEquals(limits, parsed)
        assertEquals(limits, StuckLimits.fromJson(Json.parse(limits.toJson())))
        assertNull(StuckLimits.fromJson(Json.parse("""{"flow_ms":1}""")))
    }

    @Test
    fun eventCarriesTheStuckContextAndTrail() {
        val trail = StuckTrail(2)
        trail.add("custom", "flow sign-in started", 0)
        trail.add("custom", "flow sign-in: google-picker", 1)
        trail.add("navigation", "screen LoginActivity", 2)
        val body = StuckEvent.build(mapOf("kind" to "flow", "flow" to "sign-in", "step" to null, "waited_ms" to 61_000L), trail.snapshot(), "app@1.0", null, "14", "Pixel 8", 0)
        assertEquals("stuck: flow sign-in", body["message"])
        assertEquals(mapOf("kind" to "flow", "flow" to "sign-in", "waited_ms" to 61_000L), body["stuck"])
        assertEquals(listOf("flow sign-in: google-picker", "screen LoginActivity"), (body["breadcrumbs"] as List<*>).map { (it as Map<*, *>)["message"] })
        assertEquals("1970-01-01T00:00:00.000Z", body["timestamp"])
        assertTrue((body["event_id"] as String).matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(mapOf("name" to "blipit-android", "version" to Blipit.VERSION), body["sdk"])
        assertEquals("low_memory", StuckEvent.reason(3))
        assertEquals(" a b", " " + StuckEvent.name("  a \n b ", 64))
        assertNull(StuckEvent.name("   ", 64))
    }
}
