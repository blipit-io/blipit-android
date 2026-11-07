package io.blipit.android

import android.content.SharedPreferences
import io.blipit.nuggets.Nuggets
import java.io.IOException
import java.net.URI
import okhttp3.Interceptor
import okhttp3.Response

internal class FailureGate {
    private val seen = HashMap<String, LongArray>()

    @Synchronized
    fun pass(key: String, now: Long, intervalMs: Long): Int? {
        val last = seen[key]
        if (last != null && now - last[0] < intervalMs) {
            last[1]++
            return null
        }
        val folded = last?.get(1)?.toInt() ?: 0
        if (seen.size >= 500) seen.entries.removeAll { now - it.value[0] >= intervalMs }
        seen[key] = longArrayOf(now, 0)
        return folded
    }
}

internal object HttpFailures {
    const val TYPE = "HTTPRequestFailed"
    val gate = FailureGate()

    @Volatile
    var enabled = true

    @Volatile
    var intervalMs = 60_000L

    @Volatile
    private var prefs: SharedPreferences? = null

    fun load(prefs: SharedPreferences) {
        this.prefs = prefs
        enabled = prefs.getBoolean("http_failures_enabled", enabled)
        intervalMs = prefs.getLong("http_failures_interval_ms", intervalMs)
    }

    fun configure(value: Any?) {
        val map = value as? Map<*, *> ?: return
        (map["enabled"] as? Boolean)?.let { enabled = it }
        (map["interval_ms"] as? Number)?.toLong()?.takeIf { it >= 0 }?.let { intervalMs = it }
        prefs?.edit()?.putBoolean("http_failures_enabled", enabled)?.putLong("http_failures_interval_ms", intervalMs)?.apply()
    }

    fun failed(status: Int?, error: Throwable?, canceled: Boolean): Boolean =
        if (error != null) !canceled else (status ?: 0) >= 500

    fun name(error: Throwable): String = error.javaClass.simpleName.ifEmpty { error.javaClass.name }

    fun clean(url: String): String = runCatching {
        val u = URI(url)
        URI(u.scheme, null, u.host, u.port, u.path ?: "", null, null).toString()
    }.getOrDefault(url.substringBefore('?').substringBefore('#')).take(2048)

    fun host(url: String): String = runCatching { URI(url).host }.getOrNull() ?: ""

    fun path(url: String): String = runCatching { URI(url).path }.getOrNull()?.ifEmpty { "/" } ?: "/"

    fun crumb(n: Nuggets, method: String, url: String, status: Int?, error: Throwable?, canceled: Boolean, durationMs: Long) {
        val data = linkedMapOf<String, Any>("url" to clean(url), "method" to method, "duration_ms" to durationMs)
        status?.let { data["status_code"] = it }
        if (error != null) data["error"] = name(error)
        val level = when {
            failed(status, error, canceled) -> Level.ERROR
            (status ?: 0) >= 400 -> Level.WARNING
            else -> Level.INFO
        }
        Startup.crumb(n, "http", "$method ${clean(url)}" + (status?.let { " $it" } ?: ""), level, data)
    }

    fun event(n: Nuggets, method: String, url: String, status: Int?, error: Throwable?, durationMs: Long, folded: Int): MutableMap<String, Any?> {
        val reason = error?.let(::name) ?: status?.toString() ?: "unknown"
        val http = linkedMapOf<String, Any?>("method" to method, "url" to clean(url), "duration_ms" to durationMs)
        status?.let { http["status_code"] = it }
        if (error != null) {
            http["error"] = name(error)
            error.message?.let { http["error_message"] = it.take(512) }
        }
        if (folded > 0) http["repeated"] = folded
        val event = n.event(Level.ERROR)
        event["exception"] = linkedMapOf("type" to TYPE, "value" to "$method ${host(url)}${path(url)} failed: $reason".take(4096), "handled" to true)
        event["request"] = linkedMapOf("url" to clean(url), "method" to method.take(16))
        @Suppress("UNCHECKED_CAST")
        val extra = (event["extra"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { event["extra"] = it }
        extra["http"] = http
        return event
    }

    fun report(n: Nuggets, method: String, url: String, status: Int?, error: Throwable?, canceled: Boolean, durationMs: Long, now: Long = System.currentTimeMillis()): MutableMap<String, Any?>? {
        if (!enabled || !failed(status, error, canceled)) return null
        val folded = gate.pass("$method ${host(url)}${path(url)}", now, intervalMs) ?: return null
        return event(n, method, url, status, error, durationMs, folded).also { n.send(it, true) }
    }

    fun observe(method: String, url: String, status: Int?, error: Throwable?, canceled: Boolean, durationMs: Long) {
        val n = Core.client ?: return
        if (!Core.options.httpFailures && !Core.options.networkBreadcrumbs) return
        if (host(url).equals(host(Core.options.endpoint), ignoreCase = true)) return
        runCatching {
            if (Core.options.networkBreadcrumbs) crumb(n, method, url, status, error, canceled, durationMs)
            if (Core.options.httpFailures) report(n, method, url, status, error, canceled, durationMs)
        }
    }
}

class BlipitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = System.nanoTime()
        val method = request.method
        val url = request.url.toString()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            HttpFailures.observe(method, url, null, e, chain.call().isCanceled(), (System.nanoTime() - started) / 1_000_000)
            throw e
        }
        HttpFailures.observe(method, url, response.code, null, false, (System.nanoTime() - started) / 1_000_000)
        return response
    }
}
