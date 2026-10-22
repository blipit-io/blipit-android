package io.blipit.android

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupFlowTest {
    @Test
    fun stalledStartupSendsOneGroupedWarningWithBreadcrumbs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val sent = CopyOnWriteArrayList<SentryEvent>()
        instrumentation.runOnMainSync {
            Blipit.init(app, BlipitOptions().apply {
                key = "blipit_pk_test"
                project = "7"
                endpoint = "http://127.0.0.1:9"
                startupDeadlineMs = 1_500
                configure = { it.setBeforeSend { event, _ -> sent.add(event); null } }
            })
        }
        Blipit.addBreadcrumb("signed in", category = "startup")
        ActivityScenario.launch(HostActivity::class.java).use {
            val until = SystemClock.uptimeMillis() + 10_000
            while (sent.none { e -> e.message?.formatted?.startsWith("App did not become ready") == true } && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            SystemClock.sleep(1_000)
            Blipit.reportReady()
            Blipit.reportReady()
            SystemClock.sleep(500)
        }
        val stalls = sent.filter { it.message?.formatted?.startsWith("App did not become ready") == true }
        assertEquals(1, stalls.size)
        val event = stalls[0]
        assertEquals("App did not become ready within 1.5 s", event.message!!.formatted)
        assertEquals(SentryLevel.WARNING, event.level)
        assertEquals(listOf("blipit-startup-stalled"), event.fingerprints)
        assertEquals("true", event.getTag("startup.stalled"))
        assertEquals("1500", event.getTag("startup.deadline_ms"))
        assertTrue(event.getTag("startup.screen")!!.endsWith("Activity"))
        assertTrue(event.breadcrumbs!!.any { it.message == "signed in" })
    }
}
