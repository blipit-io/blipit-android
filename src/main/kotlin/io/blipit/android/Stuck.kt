package io.blipit.android

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class StuckLimits(val startupMs: Long, val flowMs: Long, val exitReasons: Set<String>, val exitImportance: Int) {
    companion object {
        fun fromJson(value: Any?): StuckLimits? {
            val map = value as? Map<*, *> ?: return null
            val startup = (map["startup_ms"] as? Number)?.toLong() ?: return null
            val flow = (map["flow_ms"] as? Number)?.toLong() ?: return null
            val reasons = (map["exit_reasons"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            return StuckLimits(startup, flow, reasons, (map["exit_importance"] as? Number)?.toInt() ?: 0)
        }
    }

    fun toJson(): String = Json.encode(mapOf("startup_ms" to startupMs, "flow_ms" to flowMs, "exit_reasons" to exitReasons.toList(), "exit_importance" to exitImportance))
}

data class StuckFlow(val name: String, val step: String?, val waitedMs: Long)

class FlowWatch {
    private class Open(var step: String?, var spent: Long, var since: Long?)

    private val open = LinkedHashMap<String, Open>()
    private var foreground = false

    @Synchronized
    fun start(name: String, now: Long) {
        open[name] = Open(null, 0, if (foreground) now else null)
    }

    @Synchronized
    fun step(name: String, step: String): Boolean {
        val flow = open[name] ?: return false
        flow.step = step
        return true
    }

    @Synchronized
    fun end(name: String): Boolean = open.remove(name) != null

    @Synchronized
    fun isOpen(name: String): Boolean = open.containsKey(name)

    @Synchronized
    fun isEmpty(): Boolean = open.isEmpty()

    @Synchronized
    fun foreground(on: Boolean, now: Long) {
        if (on == foreground) return
        foreground = on
        for (flow in open.values) {
            if (on) {
                flow.since = now
            } else {
                flow.spent += now - (flow.since ?: now)
                flow.since = null
            }
        }
    }

    @Synchronized
    fun due(now: Long, limits: StuckLimits?): List<StuckFlow> {
        if (limits == null) return emptyList()
        val out = ArrayList<StuckFlow>()
        val it = open.entries.iterator()
        while (it.hasNext()) {
            val (name, flow) = it.next()
            val waited = flow.spent + (flow.since?.let { now - it } ?: 0)
            val limit = if (name == STARTUP) limits.startupMs else limits.flowMs
            if (limit > 0 && waited >= limit) {
                out.add(StuckFlow(name, flow.step, waited))
                it.remove()
            }
        }
        return out
    }

    companion object {
        const val STARTUP = "startup"
    }
}

class StuckTrail(private val max: Int = 30) {
    private val crumbs = ArrayDeque<Map<String, Any?>>()

    @Synchronized
    fun add(category: String, message: String, at: Long = System.currentTimeMillis()) {
        crumbs.addLast(mapOf("timestamp" to StuckEvent.iso(at), "category" to category, "message" to message))
        while (crumbs.size > max) crumbs.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Map<String, Any?>> = crumbs.toList()
}

object StuckEvent {
    private val CLEAN = Regex("\\s+")

    @JvmStatic
    fun name(value: String, max: Int): String? = CLEAN.replace(value, " ").trim().take(max).takeIf { it.isNotEmpty() }

    @JvmStatic
    fun iso(at: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at))

    @JvmStatic
    fun reason(code: Int): String = when (code) {
        1 -> "exit_self"
        2 -> "signaled"
        3 -> "low_memory"
        4 -> "crash"
        5 -> "crash_native"
        6 -> "anr"
        7 -> "initialization_failure"
        8 -> "permission_change"
        9 -> "excessive_resource_usage"
        10 -> "user_requested"
        11 -> "user_stopped"
        12 -> "dependency_died"
        13 -> "other"
        14 -> "freezer"
        15 -> "package_state_change"
        16 -> "package_updated"
        else -> "unknown"
    }

    @JvmStatic
    fun build(
        stuck: Map<String, Any?>,
        trail: List<Map<String, Any?>>,
        release: String?,
        environment: String?,
        os: String,
        model: String?,
        at: Long = System.currentTimeMillis(),
    ): Map<String, Any?> {
        val kind = stuck["kind"]
        val subject = if (kind == "exit") stuck["reason"] else stuck["flow"] ?: kind
        val body = linkedMapOf<String, Any?>(
            "event_id" to UUID.randomUUID().toString().replace("-", ""),
            "timestamp" to iso(at),
            "platform" to "java",
            "level" to "warning",
            "message" to "stuck: $kind $subject",
            "stuck" to stuck.filterValues { it != null },
            "contexts" to mapOf("os" to mapOf("name" to "Android", "version" to os)) + (model?.let { mapOf("device" to mapOf("model" to it)) } ?: emptyMap()),
            "sdk" to mapOf("name" to Blipit.SDK_NAME, "version" to Blipit.VERSION),
        )
        if (trail.isNotEmpty()) body["breadcrumbs"] = trail
        release?.let { body["release"] = it }
        environment?.let { body["environment"] = it }
        return body
    }
}

internal object Stuck {
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val FILE = "io.blipit.stuck"
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "blipit-stuck").also { it.isDaemon = true } }
    val watch = FlowWatch()
    val trail = StuckTrail()

    @Volatile
    private var limits: StuckLimits? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var context: Context? = null

    @Volatile
    private var release: String? = null

    @Volatile
    private var environment: String? = null

    @Volatile
    private var screen: String? = null

    private var ticker: ScheduledFuture<*>? = null
    private var started = 0
    private var fetchTried = 0L
    private val pending = ArrayList<Map<String, Any?>>()

    fun setup(app: Context, options: BlipitOptions) {
        if (prefs != null) return
        context = app.applicationContext
        release = options.release ?: runCatching {
            val pkg = app.packageName
            "$pkg@${app.packageManager.getPackageInfo(pkg, 0).versionName}"
        }.getOrNull()
        environment = options.environment
        val p = app.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        limits = p.getString("limits", null)?.let { runCatching { StuckLimits.fromJson(Json.parse(it)) }.getOrNull() }
        val state = ActivityManager.RunningAppProcessInfo().also { runCatching { ActivityManager.getMyMemoryState(it) } }
        if (state.importance in 1..ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) visible(+1)
        @Suppress("DEPRECATION")
        if (options.waitForReady || options.startupDeadlineMs > 0) start(FlowWatch.STARTUP)
        (app.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = visible(+1)
            override fun onActivityStopped(activity: Activity) = visible(-1)
            override fun onActivityResumed(activity: Activity) {
                val name = activity.javaClass.simpleName
                if (name != screen) trail.add("navigation", "screen $name")
                screen = name
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        io.execute {
            if (limits == null || System.currentTimeMillis() - p.getLong("fetched_at", 0L) > REFRESH_MS) {
                fetchTried = SystemClock.elapsedRealtime()
                Blipit.reporter?.stuckSettings()?.let { fresh ->
                    limits = fresh
                    p.edit().putString("limits", fresh.toJson()).putLong("fetched_at", System.currentTimeMillis()).apply()
                }
            }
            exits()
            tick()
        }
    }

    @Synchronized
    private fun visible(delta: Int) {
        val was = started > 0
        started = maxOf(0, started + delta)
        val now = started > 0
        if (was == now) return
        watch.foreground(now, SystemClock.elapsedRealtime())
        if (now) schedule()
    }

    fun start(name: String) {
        watch.start(name, SystemClock.elapsedRealtime())
        trail.add("custom", "flow $name started")
        schedule()
    }

    fun step(name: String, step: String) {
        if (watch.step(name, step)) trail.add("custom", "flow $name: $step")
    }

    fun end(name: String) {
        if (watch.end(name)) trail.add("custom", "flow $name finished")
    }

    @Synchronized
    private fun schedule() {
        if (ticker?.isDone == false) return
        ticker = io.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (limits == null && now - fetchTried > 60_000) {
            fetchTried = now
            Blipit.reporter?.stuckSettings()?.let { fresh ->
                limits = fresh
                prefs?.edit()?.putString("limits", fresh.toJson())?.putLong("fetched_at", System.currentTimeMillis())?.apply()
            }
        }
        for (flow in watch.due(now, limits)) {
            val kind = if (flow.name == FlowWatch.STARTUP) "startup" else "flow"
            queue(mapOf("kind" to kind, "flow" to if (kind == "flow") flow.name else null, "step" to flow.step, "screen" to screen, "waited_ms" to flow.waitedMs))
        }
        flush()
        synchronized(this) {
            if (watch.isEmpty()) ticker?.cancel(false)
        }
    }

    private fun queue(stuck: Map<String, Any?>) {
        val body = StuckEvent.build(stuck, trail.snapshot(), release, environment, Build.VERSION.RELEASE ?: "", Build.MODEL)
        synchronized(this) { if (pending.size < 10) pending.add(body) }
    }

    private fun flush() {
        val client = Core.client ?: return
        val batch = synchronized(this) { pending.toList().also { pending.clear() } }
        for (body in batch) client.send(LinkedHashMap(body), false)
    }

    private fun exits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val l = limits ?: return
        val p = prefs ?: return
        val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val list: List<ApplicationExitInfo> = runCatching { am.getHistoricalProcessExitReasons(null, 0, 5) }.getOrNull() ?: return
        val seen = p.getLong("exit_seen", -1L)
        val newest = list.maxOfOrNull { it.timestamp } ?: return
        p.edit().putLong("exit_seen", maxOf(newest, seen)).apply()
        if (seen < 0) return
        for (info in list.filter { it.timestamp > seen }.sortedBy { it.timestamp }) {
            val reason = StuckEvent.reason(info.reason)
            if (reason !in l.exitReasons || info.importance > l.exitImportance) continue
            queue(mapOf("kind" to "exit", "reason" to reason, "importance" to info.importance))
        }
    }
}
