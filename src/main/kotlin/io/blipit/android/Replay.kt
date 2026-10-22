package io.blipit.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

data class ReplayPlan(val mode: String, val sessionSampleRate: Double, val onErrorSampleRate: Double, val labels: Boolean) {
    companion object {
        val OFF = ReplayPlan("off", 0.0, 0.0, false)

        fun fromJson(value: Any?): ReplayPlan? {
            val map = value as? Map<*, *> ?: return null
            val mode = map["mode"] as? String ?: return null
            if (mode != "off" && mode != "errors" && mode != "sessions") return null
            val session = ((map["sessionSampleRate"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
            val error = ((map["onErrorSampleRate"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
            return ReplayPlan(mode, if (mode == "sessions") session else 0.0, if (mode == "off") 0.0 else error, map["labels"] != false)
        }
    }

    fun toJson(): String = Json.encode(mapOf("mode" to mode, "sessionSampleRate" to sessionSampleRate, "onErrorSampleRate" to onErrorSampleRate, "labels" to labels))
}

object ReplayGate {
    const val MIN_API = 26

    @JvmStatic
    fun decide(consent: Boolean, childDirected: Boolean, apiLevel: Int, server: ReplayPlan?): ReplayPlan =
        if (!consent || childDirected || apiLevel < MIN_API || server == null || server.mode == "off") ReplayPlan.OFF else server
}

class ReplayStore(private val prefs: SharedPreferences) {
    var consent: Boolean
        get() = prefs.getBoolean(CONSENT, false)
        set(value) {
            prefs.edit().putBoolean(CONSENT, value).apply()
        }

    val server: ReplayPlan?
        get() = prefs.getString(SERVER, null)?.let { runCatching { ReplayPlan.fromJson(Json.parse(it)) }.getOrNull() }

    val fetchedAt: Long
        get() = prefs.getLong(FETCHED_AT, 0L)

    fun save(plan: ReplayPlan, now: Long = System.currentTimeMillis()) {
        prefs.edit().putString(SERVER, plan.toJson()).putLong(FETCHED_AT, now).apply()
    }

    companion object {
        const val FILE = "io.blipit.replay"
        private const val CONSENT = "consent"
        private const val SERVER = "server"
        private const val FETCHED_AT = "fetched_at"
    }
}

internal object Replay {
    private const val REFRESH_MS = 60 * 60 * 1000L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "blipit-replay").also { it.isDaemon = true } }

    @Volatile
    private var store: ReplayStore? = null

    @Volatile
    private var context: Context? = null

    @Volatile
    var childDirected = false

    @Volatile
    var labelsWanted = false

    @Volatile
    private var running = "off"

    @Volatile
    private var recorder: ReplayRecorder? = null

    @Volatile
    private var resumed: java.lang.ref.WeakReference<Activity>? = null

    @Volatile
    private var lifecycle = false

    private val pendingDir: File?
        get() = context?.let { File(it.noBackupFilesDir, "blipit/replay") }

    fun setup(app: Context, blipit: BlipitOptions) {
        context = app.applicationContext
        childDirected = blipit.childDirected
        labelsWanted = blipit.replayLabels
        if (store == null) store = ReplayStore(app.applicationContext.getSharedPreferences(ReplayStore.FILE, Context.MODE_PRIVATE))
    }

    fun start(app: Application?) {
        apply()
        refresh(force = true)
        val dir = pendingDir
        val config = Core.client?.config
        if (dir != null && config != null && Build.VERSION.SDK_INT >= ReplayGate.MIN_API) {
            io.execute {
                if (store?.consent == true && !childDirected) ReplayRecorder.uploadPending(config, dir, bitRate()) else dir.deleteRecursively()
            }
        }
        if (app == null || lifecycle) return
        lifecycle = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = refresh(force = false)
            override fun onActivityResumed(activity: Activity) {
                resumed = java.lang.ref.WeakReference(activity)
                recorder?.attach(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                recorder?.detach(activity)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun consent(): Boolean = store?.consent ?: false

    fun setConsent(granted: Boolean) {
        val s = store ?: return
        s.consent = granted
        apply()
        if (granted) refresh(force = true)
    }

    fun disableForChild() {
        childDirected = true
        apply()
    }

    private fun refresh(force: Boolean) {
        val s = store ?: return
        val client = Blipit.reporter ?: return
        if (!s.consent || childDirected) return
        if (!force && System.currentTimeMillis() - s.fetchedAt < REFRESH_MS) return
        io.execute {
            val plan = client.replaySettings() ?: return@execute
            s.save(plan)
            apply()
        }
    }

    @Synchronized
    fun apply() {
        val s = store ?: return
        val plan = ReplayGate.decide(s.consent, childDirected, Build.VERSION.SDK_INT, s.server)
        if (plan.mode == "off") {
            stop()
            return
        }
        val current = recorder
        if (current != null && running == plan.mode) {
            current.labels = labelsWanted && plan.labels
            return
        }
        stop()
        val config = Core.client?.config ?: return
        val unmetered = unmetered()
        val r = ReplayRecorder(
            config,
            io,
            session = plan.mode == "sessions" && Random.nextDouble() < plan.sessionSampleRate,
            onErrorRate = plan.onErrorSampleRate,
            labels = labelsWanted && plan.labels,
            target = if (unmetered) 540 else 360,
            bitRate = bitRate(unmetered),
            pendingDir = pendingDir,
            onPaused = { synchronized(this) { stop() } },
        )
        resumed?.get()?.let { r.attach(it) }
        recorder = r
        running = plan.mode
    }

    private fun stop() {
        recorder?.stop()
        recorder = null
        running = "off"
    }

    fun onEvent(event: MutableMap<String, Any?>, error: Boolean) {
        val r = recorder ?: return
        val crash = event["level"] == "fatal" && (event["tags"] as? Map<*, *>)?.get("mechanism") == "uncaught"
        when {
            r.session -> Core.tag(event, "replay_id", r.replayId)
            error && Random.nextDouble() < r.onErrorRate -> {
                Core.tag(event, "replay_id", r.replayId)
                if (crash) r.persistForCrash() else r.flushBuffer()
            }
        }
        if (crash && r.session) r.persistForCrash()
    }

    fun screen(name: String) {
        recorder?.screen(name)
    }

    fun tap(decor: View, x: Float, y: Float) {
        recorder?.tap(decor, x, y)
    }

    fun forReport(): String? {
        val r = recorder ?: return null
        if (!r.session) r.flushBuffer()
        return r.replayId
    }

    private fun bitRate(unmetered: Boolean = unmetered()): Int = if (unmetered) 120_000 else 60_000

    private fun unmetered(): Boolean {
        val ctx = context ?: return false
        if (ctx.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching { !cm.isActiveNetworkMetered }.getOrDefault(false)
    }
}
