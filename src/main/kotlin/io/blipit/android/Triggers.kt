package io.blipit.android

import java.io.File
import kotlin.math.hypot
import kotlin.math.sqrt

class TriggerGate(private val gapMs: Long = 1500) {
    private var last = Long.MIN_VALUE / 2

    fun allow(now: Long, busy: Boolean): Boolean {
        if (busy || now - last < gapMs) return false
        last = now
        return true
    }
}

class ShakeMeter(
    private val thresholdG: Float = 2.3f,
    private val peaks: Int = 3,
    private val windowMs: Long = 1000,
    private val spacingMs: Long = 120,
) {
    private var count = 0
    private var first = 0L
    private var lastPeak = Long.MIN_VALUE / 2

    fun sample(x: Float, y: Float, z: Float, now: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / 9.80665f
        if (g < thresholdG || now - lastPeak < spacingMs) return false
        lastPeak = now
        if (count == 0 || now - first > windowMs) {
            first = now
            count = 0
        }
        count++
        if (count < peaks) return false
        count = 0
        return true
    }
}

class TwoFingerHold(private val holdMs: Long = 1000, private val slopPx: Float) {
    private var start = -1L
    private var origin: FloatArray? = null

    val holding: Boolean get() = start >= 0

    fun down(points: FloatArray, now: Long) {
        start = now
        origin = points.copyOf()
    }

    fun move(points: FloatArray) {
        val from = origin ?: return
        for (i in 0 until minOf(from.size, points.size) / 2) {
            if (hypot(points[i * 2] - from[i * 2], points[i * 2 + 1] - from[i * 2 + 1]) > slopPx) return cancel()
        }
    }

    fun cancel() {
        start = -1L
        origin = null
    }

    fun due(now: Long): Boolean {
        if (start < 0 || now - start < holdMs) return false
        cancel()
        return true
    }
}

class ReportPrompt {
    var kind: String? = null
        private set
    private var crashId: String? = null
    private var until = 0L

    val open: Boolean get() = kind != null

    fun show(kind: String, now: Long, timeoutMs: Long, crashId: String? = null): Boolean {
        if (open) return false
        this.kind = kind
        this.crashId = crashId
        until = now + timeoutMs
        return true
    }

    fun choose(report: Boolean): Pair<String, String?>? {
        val k = kind ?: return null
        val id = crashId
        kind = null
        crashId = null
        return if (report) Pair(k, id) else null
    }

    fun expire(now: Long): Boolean {
        if (!open || now < until) return false
        choose(false)
        return true
    }

    companion object {
        const val SCREENSHOT_MS = 6_000L
        const val CRASH_MS = 15_000L
    }
}

class ScreenShots<T>(private val max: Int = 3) {
    private val list = ArrayDeque<Pair<String, T>>()

    @Synchronized
    fun add(screen: String, shot: T) {
        list.removeAll { it.first == screen }
        list.addFirst(Pair(screen, shot))
        while (list.size > max + 1) list.removeLast()
    }

    @Synchronized
    fun earlier(current: String?): List<T> = list.filter { it.first != current }.take(max).map { it.second }

    @Synchronized
    fun clear() = list.clear()

    @Synchronized
    fun size(): Int = list.size
}

object ScreenKey {
    @JvmStatic
    fun of(activity: String, named: String?): String = if (named == null) activity else "$activity#$named"
}

object ReportButton {
    @JvmStatic
    fun parse(json: Any?): Boolean? {
        val map = json as? Map<*, *> ?: return null
        return map["report_button"] == true
    }

    @JvmStatic
    fun headers(id: String?, email: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        clean(id)?.let { out["X-Blipit-User-Id"] = it }
        clean(email)?.let { out["X-Blipit-User-Email"] = it }
        return out
    }

    private fun clean(v: String?): String? = v?.trim()?.take(256)?.takeIf { it.isNotEmpty() && it.none { c -> c < ' ' || c == '\u007f' } }
}

object CrashMark {
    private const val FILE = "last-crash"
    private val ID = Regex("^[0-9a-f]{32}$")

    @JvmStatic
    fun write(dir: File?, id: String?) {
        if (dir == null || id == null || !ID.matches(id)) return
        runCatching {
            dir.mkdirs()
            File(dir, FILE).writeText(id)
        }
    }

    @JvmStatic
    fun take(dir: File?): String? {
        val file = File(dir ?: return null, FILE)
        if (!file.isFile) return null
        val id = runCatching { file.readText().trim() }.getOrNull()
        file.delete()
        return id?.takeIf { ID.matches(it) }
    }
}
