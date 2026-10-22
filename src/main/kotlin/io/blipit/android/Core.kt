package io.blipit.android

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import io.blipit.nuggets.Config
import io.blipit.nuggets.Nuggets
import io.blipit.nuggets.Watchdog
import java.io.File
import java.util.UUID
import java.util.WeakHashMap
import kotlin.random.Random

internal object Core {
    private const val SESSION_IDLE_MS = 30_000L

    @Volatile
    var client: Nuggets? = null

    @Volatile
    var options = BlipitOptions()

    @Volatile
    var screen: String? = null

    @Volatile
    var pendingCrash: String? = null

    @Volatile
    private var started = 0

    private var backgroundAt = 0L
    private var installId: String? = null
    private var watchdog: Watchdog? = null
    private var registered = false
    private var nativeInstalled = false
    private val loads = WeakHashMap<Activity, LongArray>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    val foreground: Boolean
        get() = started > 0

    fun start(context: Context, options: BlipitOptions) {
        val app = context.applicationContext ?: context
        this.options = options
        val dir = File(app.noBackupFilesDir, "blipit")
        val config = Config().apply {
            key = options.key
            endpoint = options.endpoint
            release = options.release ?: defaultRelease(app)
            environment = options.environment
            sdkName = Blipit.SDK_NAME
            sdkVersion = Blipit.VERSION
            platform = "java"
            this.dir = dir
            sendCrashNow = false
            contexts.putAll(device(app))
            hook = Config.Hook { event, error ->
                Replay.onEvent(event, error)
                if (event["level"] == "fatal" && (event["tags"] as? Map<*, *>)?.get("mechanism") == "uncaught") CrashMark.write(dir, event["event_id"] as? String)
            }
        }
        val n = runCatching { Nuggets(config) }.getOrElse {
            config.dir = null
            Nuggets(config)
        }
        client?.close()
        client = n
        n.installCrashHandler()
        val report = File(dir, "native-crash.txt")
        val crashed = NativeCrash.collect(n, report, app.applicationInfo?.nativeLibraryDir)
        CrashMark.take(dir)?.let { pendingCrash = it }
        n.recoverSession(if (crashed) "crashed" else "abnormal")
        if (options.nativeCrashes && !nativeInstalled && config.dir != null) nativeInstalled = NativeCrash.install(report)
        installId = installId(dir)
        register(app)
        watchdog?.stop()
        watchdog = if (options.anr) Watchdog({ main.post(it) }, options.anrTimeoutMs, ::anr).also { it.start(500) } else null
        if (foreground && options.sessions) n.startSession(installId)
        n.outbox.kick()
    }

    private fun defaultRelease(app: Context): String? = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${app.packageName}@${info.versionName}+$code"
    }.getOrNull()

    private fun device(app: Context): Map<String, Any> {
        val metrics = app.resources.displayMetrics
        return mapOf(
            "os" to mapOf("name" to "Android", "version" to (Build.VERSION.RELEASE ?: "")),
            "device" to mapOf("model" to (Build.MODEL ?: ""), "screen" to "${metrics.widthPixels}x${metrics.heightPixels}"),
        )
    }

    private fun installId(dir: File): String? = runCatching {
        val file = File(dir, "installation")
        if (file.isFile) {
            file.readText().trim().takeIf { it.length in 8..64 } ?: throw IllegalStateException()
        } else {
            UUID.randomUUID().toString().also {
                dir.mkdirs()
                file.writeText(it)
            }
        }
    }.getOrNull()

    private fun anr(waited: Long) {
        if (!foreground || Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return
        val n = client ?: return
        val event = n.event(Level.ERROR)
        event["exception"] = linkedMapOf(
            "type" to "ApplicationNotResponding",
            "value" to "Application Not Responding for at least ${options.anrTimeoutMs} ms.",
            "stacktrace" to mapOf("frames" to Nuggets.frames(Looper.getMainLooper().thread.stackTrace, n.config.notInApp)),
            "handled" to false,
        )
        tag(event, "mechanism", "anr")
        @Suppress("UNCHECKED_CAST")
        val extra = (event["extra"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { event["extra"] = it }
        extra["blocked_ms"] = waited
        n.send(event, true)
    }

    fun tag(event: MutableMap<String, Any?>, key: String, value: String) {
        @Suppress("UNCHECKED_CAST")
        val tags = (event["tags"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { event["tags"] = it }
        if (tags.size < 32 || tags.containsKey(key)) tags[key] = value
    }

    private fun crumb(category: String, message: String, data: Map<String, Any>? = null, level: Level = Level.INFO) {
        client?.addBreadcrumb(category, message, level, data)
    }

    private fun register(app: Context) {
        val application = app as? Application ?: return
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                crumb("ui.lifecycle", activity.javaClass.simpleName, mapOf("state" to "created"))
                observe(activity)
                if (options.tracesSampleRate > 0 && Random.nextDouble() < options.tracesSampleRate) {
                    loads[activity] = longArrayOf(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                }
            }

            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) foregrounded()
            }

            override fun onActivityResumed(activity: Activity) {
                val name = activity.javaClass.simpleName
                screen = name
                crumb("navigation", name, mapOf("state" to "resumed", "to" to name))
                Replay.screen(name)
                loads.remove(activity)?.let { at -> activity.window?.decorView?.post { Traces.load(name, at) } }
            }

            override fun onActivityPaused(activity: Activity) {
                crumb("ui.lifecycle", activity.javaClass.simpleName, mapOf("state" to "paused"))
            }

            override fun onActivityStopped(activity: Activity) {
                started = maxOf(0, started - 1)
                if (started == 0) backgrounded()
            }

            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                loads.remove(activity)
            }
        })
        application.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    crumb("device.event", "memory is running low", mapOf("trim_level" to level), Level.WARNING)
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                crumb("device.event", "low memory", null, Level.WARNING)
            }
        })
    }

    private fun foregrounded() {
        crumb("app.lifecycle", "foreground")
        val n = client ?: return
        if (!options.sessions) return
        if (n.hasSession() && backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= SESSION_IDLE_MS) n.endSession("exited", true)
        if (n.hasSession()) n.markSession("paused", null) else n.startSession(installId)
        backgroundAt = 0
    }

    private fun backgrounded() {
        crumb("app.lifecycle", "background")
        backgroundAt = SystemClock.elapsedRealtime()
        client?.markSession("paused", true)
        client?.outbox?.kick()
    }

    fun screenChanged(name: String) {
        screen = name
        crumb("navigation", name, mapOf("state" to "resumed", "to" to name))
        Replay.screen(name)
    }

    fun observe(activity: Activity) {
        activity.window?.let { w -> if (w.callback != null && w.callback !is TapCallback) w.callback = TapCallback(w, w.callback) }
    }

    fun tap(window: Window, event: MotionEvent) {
        val decor = window.peekDecorView() ?: return
        val target = touched(decor, event.x.toInt(), event.y.toInt())
        if (target != null) {
            val name = runCatching { if (target.id != View.NO_ID) target.resources.getResourceEntryName(target.id) else null }.getOrNull()
            crumb("ui.click", name ?: target.javaClass.simpleName, mapOf("view" to target.javaClass.name))
        }
        Replay.tap(decor, event.x, event.y)
    }

    private fun touched(view: View, x: Int, y: Int): View? {
        if (!view.isShown) return null
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        if (x < loc[0] || y < loc[1] || x >= loc[0] + view.width || y >= loc[1] + view.height) return null
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                touched(view.getChildAt(i), x, y)?.let { return it }
            }
        }
        return if (view.isClickable) view else null
    }
}

internal class TapCallback(private val window: Window, private val inner: Window.Callback) : Window.Callback by inner {
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) runCatching { Core.tap(window, event) }
            runCatching { Reporter.touch(event) }
        }
        return inner.dispatchTouchEvent(event)
    }
}
