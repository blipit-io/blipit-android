package io.blipit.android

import io.blipit.nuggets.Config
import io.blipit.nuggets.Nuggets
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyStoreException(message: String, private val code: Int) : Exception(message) {
    fun getNumericErrorCode(): Int = code
}

class StartupTest {
    private val sent = ArrayList<Map<String, Any?>>()

    private fun nuggets(): Nuggets {
        val config = Config().apply {
            key = "blipit_pk_test"
            endpoint = "http://127.0.0.1:9"
            sdkName = Blipit.SDK_NAME
            sdkVersion = Blipit.VERSION
            hook = Config.Hook { event, _ -> synchronized(sent) { sent.add(LinkedHashMap(event)) } }
        }
        return Nuggets(config)
    }

    @After
    fun reset() {
        Core.client?.close()
        Core.client = null
        Startup.reset()
    }

    @Suppress("UNCHECKED_CAST")
    private fun failures() = synchronized(sent) { sent.filter { (it["exception"] as? Map<String, Any?>)?.get("type") == Startup.TYPE } }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun reportReadyFailedSendsOneStartupEventWithItsBreadcrumbs() {
        Core.client = nuggets()
        Blipit.addBreadcrumb("auth loaded", "startup")
        Blipit.addBreadcrumb("me request start", "startup")
        Blipit.reportReady(failed = "sessionUnreadable")
        Blipit.reportReady()
        val events = failures()
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("warning", event["level"])
        assertEquals(mapOf("type" to "StartupFailed", "value" to "sessionUnreadable", "handled" to true), event["exception"])
        assertEquals("failed", (event["tags"] as Map<String, Any?>)["startup.outcome"])
        assertEquals("sessionUnreadable", ((event["extra"] as Map<String, Any?>)["startup"] as Map<String, Any?>)["reason"])
        val crumbs = (event["breadcrumbs"] as List<Map<String, Any?>>).map { it["message"] }
        assertEquals(listOf("auth loaded", "me request start"), crumbs)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun captureStartupRecordsTheExceptionAndTheKeystoreCause() {
        Core.client = nuggets()
        Blipit.captureStartup(GeneralSecurityException("decrypt failed", KeyStoreException("key gone", 7)), "auth")
        Blipit.captureStartup(IllegalStateException("no session"), "auth")
        val events = failures()
        assertEquals(listOf("auth: GeneralSecurityException caused by KeyStoreException 7", "auth: IllegalStateException"), events.map { (it["exception"] as Map<String, Any?>)["value"] })
        val startup = (events[0]["extra"] as Map<String, Any?>)["startup"] as Map<String, Any?>
        assertEquals("java.security.GeneralSecurityException", startup["error_type"])
        assertEquals(7, startup["error_code"])
        assertEquals("auth", startup["step"])
        assertEquals(7, ((startup["causes"] as List<Map<String, Any?>>)[0])["keystore_code"])
        assertEquals("auth", (events[0]["tags"] as Map<String, Any?>)["startup.step"])
        assertNull((events[0]["exception"] as Map<String, Any?>)["stacktrace"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun callsBeforeInitAreKeptAndSentOnceStarted() {
        Blipit.addBreadcrumb("start", "startup")
        Blipit.reportReady(failed = "offline before init")
        assertTrue(failures().isEmpty())
        val n = nuggets()
        Core.client = n
        Startup.started(n)
        val event = failures().single()
        assertEquals("offline before init", (event["exception"] as Map<String, Any?>)["value"])
        assertEquals("start", (event["breadcrumbs"] as List<Map<String, Any?>>)[0]["message"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun thePreviousLaunchsBreadcrumbsRideOnTheFirstEvent() {
        val dir: File = Files.createTempDirectory("blipit-startup").toFile()
        val old = nuggets()
        Core.client = old
        Startup.load(dir)
        Startup.crumb(old, "startup", "auth loaded", Level.INFO, null)
        Startup.crumb(old, "startup", "failed", Level.ERROR, null)
        val file = File(dir, "breadcrumbs.json")
        val deadline = System.currentTimeMillis() + 3000
        while (!file.isFile && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(file.isFile)
        Startup.reset()
        Startup.load(dir)
        val first = linkedMapOf<String, Any?>()
        Startup.attachPrevious(first)
        val launch = (first["extra"] as Map<String, Any?>)["previous_launch"] as Map<String, Any?>
        assertEquals("previous launch", launch["label"])
        assertEquals(listOf("auth loaded", "failed"), (launch["breadcrumbs"] as List<Map<String, Any?>>).map { it["message"] })
        val second = linkedMapOf<String, Any?>()
        Startup.attachPrevious(second)
        assertNull(second["extra"])
        dir.deleteRecursively()
    }

    @Test
    fun stuckLimitsFallBackWhenTheFirstLaunchIsOffline() {
        val server = StuckLimits(15_000, 45_000, setOf("low_memory"), 100)
        assertEquals(StuckLimits.FALLBACK, StuckLimits.pick(null, null))
        assertEquals(server, StuckLimits.pick(null, server))
        assertEquals(server, StuckLimits.pick(server, StuckLimits.FALLBACK))
        val watch = FlowWatch()
        watch.foreground(true, 0)
        watch.start(FlowWatch.STARTUP, 0)
        assertEquals("the timer fires on time alone, no hang needed", listOf(StuckFlow(FlowWatch.STARTUP, null, 20_000)), watch.due(20_000, StuckLimits.pick(null, null)))
    }
}
