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
import io.sentry.ScreenshotStrategyType
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.SentryReplayOptions
import io.sentry.protocol.SentryId
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
    private var options: SentryOptions? = null

    @Volatile
    var childDirected = false

    @Volatile
    var labelsWanted = false

    @Volatile
    private var running = "off"

    @Volatile
    private var lifecycle = false

    fun setup(app: Context, sentry: SentryOptions, blipit: BlipitOptions) {
        context = app.applicationContext
        options = sentry
        childDirected = blipit.childDirected
        labelsWanted = blipit.replayLabels
        val replay = sentry.sessionReplay
        replay.sessionSampleRate = 0.0
        replay.onErrorSampleRate = 0.0
        mask(replay, false)
        if (store == null) store = ReplayStore(app.applicationContext.getSharedPreferences(ReplayStore.FILE, Context.MODE_PRIVATE))
    }

    fun start(app: Application?) {
        apply()
        refresh(force = true)
        if (app == null || lifecycle) return
        lifecycle = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = refresh(force = false)
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
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
        val sentry = options ?: return
        val s = store ?: return
        val plan = ReplayGate.decide(s.consent, childDirected, Build.VERSION.SDK_INT, s.server)
        val replay = sentry.sessionReplay
        val api = runCatching { Sentry.replay() }.getOrNull() ?: return
        if (plan.mode == "off") {
            replay.sessionSampleRate = 0.0
            replay.onErrorSampleRate = 0.0
            if (running != "off") runCatching { api.stop() }
            running = "off"
            return
        }
        mask(replay, labelsWanted && plan.labels)
        replay.quality = if (unmetered()) SentryReplayOptions.SentryReplayQuality.MEDIUM else SentryReplayOptions.SentryReplayQuality.LOW
        replay.onErrorSampleRate = plan.onErrorSampleRate
        replay.sessionSampleRate = plan.sessionSampleRate
        if (running == plan.mode) return
        if (running != "off") runCatching { api.stop() }
        if (plan.mode == "sessions" && Random.nextDouble() < plan.sessionSampleRate) api.start() else api.startBuffering()
        running = plan.mode
    }

    fun forReport(): String? {
        if (running == "off") return null
        val controller = options?.replayController ?: return null
        if (!controller.isRecording) return null
        val id = runCatching { controller.captureReplay(false) }.getOrNull() ?: return null
        return if (id == SentryId.EMPTY_ID) null else id.toString()
    }

    private fun mask(replay: SentryReplayOptions, labels: Boolean) {
        val readable = labels && !composeLoaded()
        replay.screenshotStrategy = if (readable) ScreenshotStrategyType.PIXEL_COPY else ScreenshotStrategyType.CANVAS
        replay.setMaskAllImages(true)
        replay.setMaskAllText(!readable)
        replay.addMaskViewClass("android.widget.EditText")
        replay.addMaskViewClass("android.webkit.WebView")
        replay.addMaskViewClass("android.widget.VideoView")
        replay.addMaskViewClass("android.view.SurfaceView")
        replay.addMaskViewClass("android.view.TextureView")
    }

    private fun composeLoaded(): Boolean = runCatching { Class.forName("androidx.compose.ui.platform.ComposeView") }.isSuccess

    private fun unmetered(): Boolean {
        val ctx = context ?: return false
        if (ctx.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching { !cm.isActiveNetworkMetered }.getOrDefault(false)
    }
}
