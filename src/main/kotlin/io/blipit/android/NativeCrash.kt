package io.blipit.android

import io.blipit.nuggets.Nuggets
import java.io.File

internal object NativeCrash {
    private val NAMES = mapOf(
        4 to "SIGILL",
        5 to "SIGTRAP",
        6 to "SIGABRT",
        7 to "SIGBUS",
        8 to "SIGFPE",
        11 to "SIGSEGV",
    )
    private val TEXT = mapOf(
        "SIGILL" to "Illegal instruction",
        "SIGTRAP" to "Trap",
        "SIGABRT" to "Abort",
        "SIGBUS" to "Bus error",
        "SIGFPE" to "Floating point exception",
        "SIGSEGV" to "Segmentation violation",
    )

    @JvmStatic
    private external fun installHandler(path: String): Boolean

    fun install(report: File): Boolean = runCatching {
        System.loadLibrary("blipit-nuggets")
        report.parentFile?.mkdirs()
        installHandler(report.absolutePath)
    }.getOrDefault(false)

    class Region(val start: Long, val end: Long, val offset: Long, val path: String)

    class Parsed(
        val signal: String,
        val code: Int,
        val address: String,
        val thread: String?,
        val tid: Long?,
        val registers: Map<String, String>,
        val frames: List<Map<String, Any>>,
        val libraries: List<String>,
    )

    @JvmStatic
    fun parse(text: String, appLibDir: String?): Parsed? {
        val head = text.substringBefore("\nmaps\n")
        val maps = text.substringAfter("\nmaps\n", "").lineSequence().mapNotNull(::mapLine).toList()
        val values = HashMap<String, String>()
        val registers = LinkedHashMap<String, String>()
        val pcs = ArrayList<Long>()
        for (line in head.lineSequence()) {
            val key = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "").trim()
            when (key) {
                "reg" -> registers[rest.substringBefore(' ')] = rest.substringAfter(' ')
                "frame" -> hex(rest)?.let { if (it != 0L) pcs.add(it) }
                "" -> {}
                else -> values[key] = rest
            }
        }
        val signo = values["signal"]?.toIntOrNull() ?: return null
        val signal = NAMES[signo] ?: "SIG$signo"
        val pc = hex(values["pc"] ?: "")
        val unwound = if (pc != null && pcs.firstOrNull() == pc) pcs.drop(1) else pcs
        val at = if (pc == null) -1 else unwound.indexOfFirst { it == pc || it == pc + 1 }
        val chain = when {
            at >= 0 -> unwound.drop(at)
            pc != null -> listOf(pc) + unwound.filter { addr -> maps.none { addr >= it.start && addr < it.end && it.path.endsWith("libblipit-nuggets.so") } }
            else -> unwound
        }.take(Nuggets.MAX_FRAMES)
        val used = LinkedHashSet<String>()
        val frames = chain.map { addr ->
            val m = maps.firstOrNull { addr >= it.start && addr < it.end }
            m?.path?.takeIf { it.startsWith("/") }?.let { used.add(it) }
            val file = m?.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "unknown"
            val rel = if (m != null) addr - m.start + m.offset else addr
            linkedMapOf<String, Any>(
                "filename" to file,
                "function" to "0x" + java.lang.Long.toHexString(rel),
                "in_app" to (m != null && appLibDir != null && m.path.startsWith(appLibDir)),
            )
        }.reversed()
        val libraries = (used + maps.map { it.path }.filter { appLibDir != null && it.startsWith(appLibDir) && it.endsWith(".so") }).distinct().take(100)
        return Parsed(signal, values["code"]?.toIntOrNull() ?: 0, values["addr"] ?: "0x0", values["thread"], values["tid"]?.toLongOrNull(), registers, frames, libraries)
    }

    private fun hex(s: String): Long? = s.removePrefix("0x").takeIf { it.isNotEmpty() }?.let { runCatching { java.lang.Long.parseUnsignedLong(it, 16) }.getOrNull() }

    private fun mapLine(line: String): Region? {
        val parts = line.trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 6 || !parts[1].contains('x')) return null
        val range = parts[0].split('-')
        if (range.size != 2) return null
        return Region(hex(range[0]) ?: return null, hex(range[1]) ?: return null, hex(parts[2]) ?: 0, parts[5])
    }

    fun collect(n: Nuggets, report: File, appLibDir: String?): Boolean {
        if (!report.isFile) return false
        val at = report.lastModified()
        val parsed = runCatching { parse(report.readText(), appLibDir) }.getOrNull()
        report.delete()
        if (parsed == null) return false
        val event = n.event(Level.FATAL)
        event["timestamp"] = Nuggets.iso(if (at > 0) at else System.currentTimeMillis())
        val where = parsed.thread?.let { " in thread $it" } ?: ""
        event["exception"] = linkedMapOf(
            "type" to parsed.signal,
            "value" to "${TEXT[parsed.signal] ?: "Signal"} (code ${parsed.code}) at ${parsed.address}$where",
            "stacktrace" to mapOf("frames" to parsed.frames),
            "handled" to false,
        ).also { if (parsed.frames.isEmpty()) it.remove("stacktrace") }
        Core.tag(event, "mechanism", "native")
        @Suppress("UNCHECKED_CAST")
        val extra = (event["extra"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { event["extra"] = it }
        extra["native"] = linkedMapOf("thread" to parsed.thread, "tid" to parsed.tid, "registers" to parsed.registers, "libraries" to parsed.libraries)
        event.remove("breadcrumbs")
        n.outbox.persist("/api/v1/events", event)
        CrashMark.write(report.parentFile, event["event_id"] as? String)
        return true
    }
}
