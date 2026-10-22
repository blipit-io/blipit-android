package io.blipit.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggersTest {
    @Test
    fun gateOpensOneReporterPerShake() {
        val gate = TriggerGate(1500)
        assertTrue(gate.allow(10_000, false))
        assertFalse(gate.allow(10_400, false))
        assertFalse(gate.allow(11_499, false))
        assertTrue(gate.allow(11_500, false))
        assertFalse(gate.allow(20_000, true))
        assertTrue(gate.allow(20_001, false))
    }

    @Test
    fun shakeNeedsThreeSpacedPeaksInOneSecond() {
        val meter = ShakeMeter()
        val hard = 30f
        assertFalse(meter.sample(0f, 9.8f, 0f, 0))
        assertFalse(meter.sample(hard, 0f, 0f, 100))
        assertFalse(meter.sample(hard, 0f, 0f, 150))
        assertFalse(meter.sample(hard, 0f, 0f, 300))
        assertTrue(meter.sample(hard, 0f, 0f, 500))
        assertFalse(meter.sample(hard, 0f, 0f, 700))
        assertFalse(meter.sample(hard, 0f, 0f, 2000))
        assertFalse(meter.sample(hard, 0f, 0f, 2200))
        assertFalse(meter.sample(hard, 0f, 0f, 3500))
    }

    @Test
    fun twoFingerHoldFiresOnlyWhenHeldStill() {
        val hold = TwoFingerHold(1000, 24f)
        hold.down(floatArrayOf(100f, 100f, 200f, 200f), 0)
        assertFalse(hold.due(500))
        hold.move(floatArrayOf(110f, 105f, 195f, 210f))
        assertTrue(hold.due(1000))
        assertFalse(hold.due(1500))

        hold.down(floatArrayOf(100f, 100f, 200f, 200f), 2000)
        hold.move(floatArrayOf(160f, 100f, 200f, 200f))
        assertFalse(hold.holding)
        assertFalse(hold.due(3500))

        hold.down(floatArrayOf(0f, 0f, 50f, 50f), 4000)
        hold.cancel()
        assertFalse(hold.due(6000))
    }

    @Test
    fun promptShowsOnceReportsOrTimesOut() {
        val prompt = ReportPrompt()
        assertTrue(prompt.show("screenshot", 0, ReportPrompt.SCREENSHOT_MS))
        assertFalse(prompt.show("screenshot", 10, ReportPrompt.SCREENSHOT_MS))
        assertFalse(prompt.expire(5_999))
        assertEquals(Pair("screenshot", null), prompt.choose(true))
        assertFalse(prompt.open)
        assertNull(prompt.choose(true))

        assertTrue(prompt.show("screenshot", 100, ReportPrompt.SCREENSHOT_MS))
        assertNull(prompt.choose(false))

        val id = "0123456789abcdef0123456789abcdef"
        assertTrue(prompt.show("crash", 1_000, ReportPrompt.CRASH_MS, id))
        assertFalse(prompt.expire(15_999))
        assertTrue(prompt.expire(16_000))
        assertFalse(prompt.open)

        assertTrue(prompt.show("crash", 20_000, ReportPrompt.CRASH_MS, id))
        assertEquals(Pair("crash", id), prompt.choose(true))
    }

    @Test
    fun pickerKeepsThreeEarlierDistinctScreensNewestFirst() {
        val shots = ScreenShots<String>(3)
        shots.add("Home", "home-1")
        shots.add("Cart", "cart-1")
        shots.add("Home", "home-2")
        assertEquals(listOf("cart-1"), shots.earlier("Home"))
        shots.add("Item", "item-1")
        shots.add("Search", "search-1")
        shots.add("Profile", "profile-1")
        assertEquals(4, shots.size())
        assertEquals(listOf("search-1", "item-1", "home-2"), shots.earlier("Profile"))
        assertEquals(listOf("profile-1", "search-1", "item-1"), shots.earlier("Other"))
        shots.clear()
        assertTrue(shots.earlier(null).isEmpty())
    }

    @Test
    fun namedScreensInOneActivityAreDistinctPickerEntries() {
        val shots = ScreenShots<String>(3)
        shots.add(ScreenKey.of("app.Main", null), "start")
        shots.add(ScreenKey.of("app.Main", "home"), "home")
        shots.add(ScreenKey.of("app.Main", "cart"), "cart")
        shots.add(ScreenKey.of("app.Main", "home"), "home-again")
        assertEquals("app.Main#home", ScreenKey.of("app.Main", "home"))
        assertEquals("app.Main", ScreenKey.of("app.Main", null))
        assertEquals(listOf("cart", "start"), shots.earlier(ScreenKey.of("app.Main", "home")))
    }

    @Test
    fun buttonSettingParsesAndSendsOnlyCleanUserHeaders() {
        assertEquals(true, ReportButton.parse(mapOf("report_button" to true)))
        assertEquals(false, ReportButton.parse(mapOf("report_button" to "yes")))
        assertEquals(false, ReportButton.parse(mapOf("recording" to true)))
        assertNull(ReportButton.parse("nope"))
        assertNull(ReportButton.parse(null))
        assertEquals(mapOf("X-Blipit-User-Id" to "42", "X-Blipit-User-Email" to "ana@example.com"), ReportButton.headers(" 42 ", "ana@example.com"))
        assertEquals(emptyMap<String, String>(), ReportButton.headers(null, "a\r\nX-Evil: 1"))
        assertEquals(emptyMap<String, String>(), ReportButton.headers("", "  "))
    }

    @Test
    fun crashIdIsConsumedOnce() {
        val dir: File = Files.createTempDirectory("blipit-crash").toFile()
        val id = "0123456789abcdef0123456789abcdef"
        assertNull(CrashMark.take(dir))
        CrashMark.write(dir, "not-an-id")
        assertNull(CrashMark.take(dir))
        CrashMark.write(dir, id)
        assertEquals(id, CrashMark.take(dir))
        assertNull(CrashMark.take(dir))
        dir.deleteRecursively()
    }

    @Test
    fun crashReportCarriesTheCrashEventId() {
        val id = "0123456789abcdef0123456789abcdef"
        val body = ReportPayload.feedback("", "it closed", null, emptyMap(), emptyList(), 0, 0, 0, 0, "en", null, id)
        assertEquals(id, body["event_id"])
        assertFalse(ReportPayload.feedback("", "x", null, emptyMap(), emptyList(), 0, 0, 0, 0, "en", null, "bad").containsKey("event_id"))
    }
}
