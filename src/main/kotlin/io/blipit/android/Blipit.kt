package io.blipit.android

import android.app.Activity
import android.app.Application
import android.content.Context

typealias Level = io.blipit.Level

class BlipitOptions {
    var key: String = ""
    var project: String = ""
    var environment: String? = null
    var release: String? = null
    var tracesSampleRate: Double = 0.0
    var endpoint: String = Blipit.DEFAULT_ENDPOINT
    var childDirected: Boolean = false
    var replayLabels: Boolean = false
    var waitForReady: Boolean = false

    @Deprecated("Use waitForReady = true. The time limit now comes from Blipit.")
    var startupDeadlineMs: Long = 0
    var anr: Boolean = true
    var anrTimeoutMs: Long = 5000
    var nativeCrashes: Boolean = true
    var sessions: Boolean = true
    var shakeToReport: Boolean = false
    var screenshotToReport: Boolean = false
    var twoFingerReport: Boolean = false
    var reportAfterCrash: Boolean = false
    var screenPicker: Boolean = true
}

object Blipit {
    const val DEFAULT_ENDPOINT = "https://in.blipit.io"
    const val VERSION = "0.6.0"
    const val SDK_NAME = "blipit-android"
    const val PACKAGE_NAME = "maven:com.github.blipit-io:blipit-android"

    @Volatile
    internal var reporter: ReportClient? = null

    @JvmStatic
    fun init(context: Context, options: BlipitOptions) {
        require(options.key.isNotEmpty()) { "Blipit.init needs the project's public key" }
        require(options.project.isNotEmpty()) { "Blipit.init needs the project id" }
        reporter = ReportClient(options.endpoint, options.key)
        Core.start(context, options)
        Replay.setup(context, options)
        val app = context.applicationContext as? Application
        Reporter.attach(app)
        Reporter.refreshButton()
        Replay.start(app)
        Stuck.setup(context, options)
    }

    fun init(context: Context, configure: BlipitOptions.() -> Unit) {
        init(context, BlipitOptions().apply(configure))
    }

    @JvmStatic
    @JvmOverloads
    fun showReport(activity: Activity? = null) = Reporter.show(activity)

    @JvmStatic
    @JvmOverloads
    fun showReportButton(activity: Activity? = null) = Reporter.showButton(activity)

    @JvmStatic
    fun hideReportButton() = Reporter.hideButton()

    @JvmStatic
    fun setReportHandler(handler: ((trigger: String, crashId: String?) -> Unit)?) {
        Reporter.handler = handler
    }

    @JvmStatic
    fun setScreen(name: String) {
        val clean = StuckEvent.name(name, 128) ?: return
        Core.screenChanged(clean)
        Reporter.screen(clean)
    }

    @JvmStatic
    fun attach(activity: Activity) = Reporter.attachActivity(activity)

    @JvmStatic
    fun lastCrashId(): String? = Core.pendingCrash.also { Core.pendingCrash = null }

    @JvmStatic
    fun promptAfterCrash(eventId: String) = Reporter.promptCrash(eventId)

    @JvmStatic
    fun setReplayConsent(granted: Boolean) {
        Replay.setConsent(granted)
        if (!granted) Reporter.forgetScreens()
    }

    @JvmStatic
    fun hasReplayConsent(): Boolean = Replay.consent()

    @JvmStatic
    fun disableReplayForChildApp() = Replay.disableForChild()

    @JvmStatic
    fun reportReady() = Stuck.end(FlowWatch.STARTUP)

    @JvmStatic
    fun startFlow(name: String) {
        StuckEvent.name(name, 64)?.let { Stuck.start(it) }
    }

    @JvmStatic
    fun flowStep(name: String, step: String) {
        val flow = StuckEvent.name(name, 64) ?: return
        StuckEvent.name(step, 128)?.let { Stuck.step(flow, it) }
    }

    @JvmStatic
    fun endFlow(name: String) {
        StuckEvent.name(name, 64)?.let { Stuck.end(it) }
    }

    @JvmStatic
    fun captureException(throwable: Throwable): String? = Core.client?.captureException(throwable, true, Level.ERROR)

    @JvmStatic
    @JvmOverloads
    fun captureMessage(message: String, level: Level = Level.INFO): String? = Core.client?.captureMessage(message, level)

    @JvmStatic
    @JvmOverloads
    fun setUser(id: String?, email: String? = null, username: String? = null) {
        Core.client?.setUser(id, email, username)
        Reporter.user(id, email)
    }

    @JvmStatic
    fun setTag(key: String, value: String) {
        Core.client?.setTag(key, value)
    }

    @JvmStatic
    fun setExtra(key: String, value: Any?) {
        Core.client?.setExtra(key, value)
    }

    @JvmStatic
    @JvmOverloads
    fun addBreadcrumb(message: String, category: String? = null, level: Level = Level.INFO) {
        Core.client?.addBreadcrumb(category, message, level, null)
    }

    @JvmStatic
    @JvmOverloads
    fun securityContext(
        kind: String,
        actor: String,
        outcome: String? = null,
        actorId: String? = null,
        ip: String? = null,
        userAgent: String? = null,
        target: String? = null,
    ): Map<String, String> {
        val context = linkedMapOf("kind" to kind, "actor" to actor)
        outcome?.let { context["outcome"] = it }
        actorId?.let { context["actor_id"] = it }
        ip?.let { context["ip"] = it }
        userAgent?.let { context["user_agent"] = it }
        target?.let { context["target"] = it }
        return context
    }

    @JvmStatic
    @JvmOverloads
    fun captureSecurity(
        kind: String,
        actor: String,
        outcome: String? = null,
        actorId: String? = null,
        ip: String? = null,
        userAgent: String? = null,
        target: String? = null,
    ): String? {
        val context: Map<String, Any> = securityContext(kind, actor, outcome, actorId, ip, userAgent, target)
        val level = if (kind == "login_failed" || kind == "login_blocked") Level.WARNING else Level.INFO
        return Core.client?.captureSecurity(LinkedHashMap(context), "$kind for $actor", level)
    }

    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMillis: Long = 2000): Boolean = Core.client?.flush(timeoutMillis) ?: true
}
