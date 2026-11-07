package io.blipit.android

import io.blipit.nuggets.Nuggets
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object Startup {
    const val TYPE = "StartupFailed"
    private val began = System.currentTimeMillis()
    private val early = ArrayList<(Nuggets) -> Unit>()
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "blipit-crumbs").also { it.isDaemon = true } }
    private val saving = AtomicBoolean(false)

    @Volatile
    private var file: File? = null

    @Volatile
    private var previous: Map<String, Any?>? = null

    fun whenStarted(work: (Nuggets) -> Unit) {
        val n = synchronized(this) {
            Core.client ?: run {
                if (early.size < 100) early.add(work)
                null
            }
        } ?: return
        work(n)
    }

    fun started(n: Nuggets) {
        val work = synchronized(this) { early.toList().also { early.clear() } }
        for (item in work) runCatching { item(n) }
    }

    fun load(dir: File?) {
        val f = dir?.let { File(it, "breadcrumbs.json") } ?: return
        file = f
        val saved = runCatching { Json.parse(f.readText()) as? Map<*, *> }.getOrNull() ?: return
        val crumbs = (saved["breadcrumbs"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
        if (crumbs.isEmpty()) return
        val launch = linkedMapOf<String, Any?>("label" to "previous launch", "breadcrumbs" to crumbs.takeLast(50))
        (saved["release"] as? String)?.let { launch["release"] = it }
        synchronized(this) { previous = launch }
    }

    fun crumb(n: Nuggets, category: String?, message: String, level: Level, data: Map<String, Any>?) {
        n.addBreadcrumb(category, message, level, data)
        saveSoon()
    }

    fun saveSoon() {
        val f = file ?: return
        if (!saving.compareAndSet(false, true)) return
        saver.schedule({
            saving.set(false)
            val n = Core.client ?: return@schedule
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(Json.encode(mapOf("release" to n.config.release, "breadcrumbs" to n.breadcrumbs())))
                tmp.renameTo(f)
            }
        }, 250, TimeUnit.MILLISECONDS)
    }

    fun attachPrevious(event: MutableMap<String, Any?>) {
        val launch = synchronized(this) { previous.also { previous = null } } ?: return
        @Suppress("UNCHECKED_CAST")
        val extra = (event["extra"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { event["extra"] = it }
        extra["previous_launch"] = launch
    }

    fun reset() {
        synchronized(this) {
            early.clear()
            previous = null
        }
        file = null
    }

    fun event(n: Nuggets, value: String, details: Map<String, Any?>, step: String?): MutableMap<String, Any?> {
        val event = n.event(Level.WARNING)
        event["exception"] = linkedMapOf("type" to TYPE, "value" to value.take(4096), "handled" to true)
        @Suppress("UNCHECKED_CAST")
        val tags = (event["tags"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { event["tags"] = it }
        tags["startup.outcome"] = "failed"
        step?.let { tags["startup.step"] = it.take(256) }
        val startup = LinkedHashMap(details)
        startup["outcome"] = "failed"
        startup["since_start_ms"] = System.currentTimeMillis() - began
        step?.let { startup["step"] = it }
        @Suppress("UNCHECKED_CAST")
        val extra = (event["extra"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { event["extra"] = it }
        extra["startup"] = startup
        return event
    }

    fun failed(n: Nuggets, reason: String): String? = n.send(event(n, reason, mapOf("reason" to reason), null), false)

    private fun keystore(t: Throwable): Boolean {
        val name = t.javaClass.name
        return name.startsWith("android.security.") || name.startsWith("java.security.") || name.startsWith("javax.crypto.") || t.javaClass.simpleName.contains("KeyStore", ignoreCase = true)
    }

    private fun code(t: Throwable): Int? = runCatching { t.javaClass.getMethod("getNumericErrorCode").invoke(t) as? Int }.getOrNull()

    fun details(t: Throwable): Pair<String, Map<String, Any?>> {
        val details = linkedMapOf<String, Any?>("error_type" to t.javaClass.name)
        t.message?.let { details["message"] = it.take(1024) }
        val chain = generateSequence(t.cause) { it.cause.takeIf { c -> c !== it } }.take(5).toList()
        if (chain.isNotEmpty()) {
            details["causes"] = chain.map { c ->
                linkedMapOf<String, Any?>("type" to c.javaClass.name).also { m ->
                    c.message?.let { m["message"] = it.take(512) }
                    code(c)?.let { m["keystore_code"] = it }
                }
            }
        }
        val store = (listOf(t) + chain).firstOrNull { code(it) != null } ?: chain.lastOrNull { keystore(it) }
        val storeCode = store?.let(::code)
        storeCode?.let { details["error_code"] = it }
        var summary = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
        if (store != null && store !== t) summary += " caused by ${store.javaClass.simpleName}"
        if (storeCode != null) summary += " $storeCode"
        return summary to details
    }

    fun capture(n: Nuggets, t: Throwable, step: String): String? {
        val (summary, details) = details(t)
        return n.send(event(n, "$step: $summary", details, step), false)
    }
}
