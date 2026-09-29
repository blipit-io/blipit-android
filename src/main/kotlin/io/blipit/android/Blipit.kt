package io.blipit.android

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.SentryId
import io.sentry.protocol.User

class BlipitOptions {
    var key: String = ""
    var project: String = ""
    var environment: String? = null
    var release: String? = null
    var tracesSampleRate: Double = 0.0
    var endpoint: String = Blipit.DEFAULT_ENDPOINT
    var configure: ((SentryAndroidOptions) -> Unit)? = null
}

object Blipit {
    const val DEFAULT_ENDPOINT = "https://in.blipit.io"

    @JvmStatic
    @JvmOverloads
    fun dsn(key: String, project: String, endpoint: String = DEFAULT_ENDPOINT): String {
        val host = endpoint.substringAfter("://").trimEnd('/')
        val scheme = if (endpoint.startsWith("http://")) "http" else "https"
        return "$scheme://$key@$host/$project"
    }

    @JvmStatic
    fun init(context: Context, options: BlipitOptions) {
        require(options.key.isNotEmpty()) { "Blipit.init needs the project's public key" }
        require(options.project.isNotEmpty()) { "Blipit.init needs the project id" }
        SentryAndroid.init(context) { sentry ->
            apply(sentry, options)
            options.configure?.invoke(sentry)
        }
    }

    fun init(context: Context, configure: BlipitOptions.() -> Unit) {
        init(context, BlipitOptions().apply(configure))
    }

    @JvmStatic
    fun apply(sentry: SentryOptions, options: BlipitOptions) {
        sentry.dsn = dsn(options.key, options.project, options.endpoint)
        sentry.environment = options.environment
        sentry.release = options.release
        sentry.tracesSampleRate = options.tracesSampleRate
        sentry.isSendDefaultPii = false
    }

    @JvmStatic
    fun captureException(throwable: Throwable): SentryId = Sentry.captureException(throwable)

    @JvmStatic
    @JvmOverloads
    fun captureMessage(message: String, level: SentryLevel = SentryLevel.INFO): SentryId = Sentry.captureMessage(message, level)

    @JvmStatic
    @JvmOverloads
    fun setUser(id: String?, email: String? = null, username: String? = null) {
        Sentry.setUser(User().also {
            it.id = id
            it.email = email
            it.username = username
        })
    }

    @JvmStatic
    fun setTag(key: String, value: String) = Sentry.setTag(key, value)

    @JvmStatic
    @JvmOverloads
    fun addBreadcrumb(message: String, category: String? = null, level: SentryLevel = SentryLevel.INFO) {
        Sentry.addBreadcrumb(Breadcrumb().also {
            it.message = message
            it.category = category
            it.level = level
        })
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
    ): SentryId {
        val context = securityContext(kind, actor, outcome, actorId, ip, userAgent, target)
        val level = if (kind == "login_failed" || kind == "login_blocked") SentryLevel.WARNING else SentryLevel.INFO
        return Sentry.captureMessage("$kind for $actor", level) { scope ->
            scope.setContexts("security", context)
            scope.setTag("security.kind", kind)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMillis: Long = 2000) = Sentry.flush(timeoutMillis)
}
