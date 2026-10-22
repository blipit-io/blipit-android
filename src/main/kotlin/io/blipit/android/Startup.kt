package io.blipit.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class StartupClock(deadlineMs: Long, private val now: () -> Long) {
    @Volatile
    var deadlineMs = deadlineMs
    private var since = -1L
    private var spent = 0L
    var ready = false
        private set
    var stalled = false
        private set

    val running: Boolean
        @Synchronized get() = since >= 0

    @Synchronized
    fun foreground() {
        if (since < 0) since = now()
    }

    @Synchronized
    fun background() {
        if (since >= 0) {
            spent += now() - since
            since = -1
        }
    }

    @Synchronized
    fun elapsed(): Long = spent + if (since >= 0) now() - since else 0

    @Synchronized
    fun remaining(): Long = deadlineMs - elapsed()

    @Synchronized
    fun armed(): Boolean = deadlineMs > 0 && !ready && !stalled && since >= 0

    @Synchronized
    fun check(): Boolean {
        if (!armed() || elapsed() < deadlineMs) return false
        stalled = true
        return true
    }

    @Synchronized
    fun ready(): Long? {
        if (ready) return null
        ready = true
        return elapsed()
    }
}

internal object Startup {
    const val FINGERPRINT = "blipit-startup-stalled"
    const val DEFAULT_DEADLINE_MS = 20_000L

    @Volatile
    private var clock: StartupClock? = null
    private var timer: ScheduledExecutorService? = null
    private var pending: ScheduledFuture<*>? = null
    private var started = 0

    @Volatile
    private var screen: String? = null

    fun message(deadlineMs: Long): String {
        val seconds = if (deadlineMs % 1000 == 0L) (deadlineMs / 1000).toString() else (deadlineMs / 1000.0).toString()
        return "App did not become ready within $seconds s"
    }

    @Synchronized
    fun start(app: Application?, deadlineMs: Long) {
        clock?.let {
            it.deadlineMs = deadlineMs
            arm()
            return
        }
        if (app == null) return
        clock = StartupClock(deadlineMs) { System.nanoTime() / 1_000_000 }
        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = started()
                override fun onActivityStopped(activity: Activity) = stopped(activity.isChangingConfigurations)
                override fun onActivityResumed(activity: Activity) {
                    screen = activity.javaClass.simpleName
                }
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityCreated(activity: Activity, state: Bundle?) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        } catch (_: RuntimeException) {
        }
    }

    @Synchronized
    private fun started() {
        started++
        clock?.foreground()
        arm()
    }

    @Synchronized
    private fun stopped(changingConfigurations: Boolean) {
        started = maxOf(0, started - 1)
        if (started == 0 && !changingConfigurations) {
            clock?.background()
            arm()
        }
    }

    @Synchronized
    private fun arm() {
        pending?.cancel(false)
        pending = null
        val c = clock ?: return
        if (!c.armed()) return
        try {
            val t = timer ?: Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "blipit-startup").also { it.isDaemon = true } }.also { timer = it }
            pending = t.schedule({ tick() }, maxOf(0L, c.remaining()), TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
        }
    }

    private fun tick() {
        val c = clock ?: return
        val deadlineMs = c.deadlineMs
        if (c.check()) capture(deadlineMs) else arm()
    }

    private fun capture(deadlineMs: Long) {
        try {
            val current = screen
            Sentry.captureMessage(message(deadlineMs), SentryLevel.WARNING) { scope ->
                scope.fingerprint = listOf(FINGERPRINT)
                scope.setTag("startup.stalled", "true")
                scope.setTag("startup.deadline_ms", deadlineMs.toString())
                if (current != null) scope.setTag("startup.screen", current)
            }
        } catch (_: Throwable) {
        }
    }

    fun ready() {
        try {
            val c = clock
            val elapsed = c?.ready()
            if (c != null && elapsed == null) return
            synchronized(this) {
                pending?.cancel(false)
                pending = null
            }
            Sentry.reportFullyDisplayed()
            if (c == null || elapsed == null) return
            Sentry.addBreadcrumb(Breadcrumb().also {
                it.category = "startup"
                it.message = if (c.stalled) "App became ready after $elapsed ms, past the startup deadline" else "App ready after $elapsed ms"
                it.level = SentryLevel.INFO
                it.setData("ready_ms", elapsed)
                it.setData("stalled", c.stalled)
            })
        } catch (_: Throwable) {
        }
    }
}
