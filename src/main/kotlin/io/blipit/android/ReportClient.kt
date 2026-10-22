package io.blipit.android

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToLong

internal object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> {
                out.append('"')
                for (c in value) {
                    when {
                        c == '"' -> out.append("\\\"")
                        c == '\\' -> out.append("\\\\")
                        c == '\n' -> out.append("\\n")
                        c == '\r' -> out.append("\\r")
                        c == '\t' -> out.append("\\t")
                        c < ' ' -> out.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                        else -> out.append(c)
                    }
                }
                out.append('"')
            }
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Number -> {
                val d = value.toDouble()
                out.append(if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString())
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> write(out, value.toString())
        }
    }

    fun parse(text: String): Any? = Parser(text).run { skip(); value() }

    private class Parser(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skip()
            return when (val c = s.getOrNull(i)) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                null -> null
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad json at $i")
            }
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skip()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skip()
                val key = str()
                skip()
                i++
                out[key] = value()
                skip()
                if (s[i++] == '}') return out
            }
        }

        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skip()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skip()
                if (s[i++] == ']') return out
            }
        }

        fun str(): String {
            val out = StringBuilder()
            i++
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val e = s[i]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> { out.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> out.append(e)
                    }
                } else {
                    out.append(s[i])
                }
                i++
            }
            i++
            return out.toString()
        }

        fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDouble()
        }
    }
}

data class ReportShape(val tool: Tool, val points: MutableList<FloatArray>, val color: String = "#FF3B30") {
    enum class Tool(val id: String) { PEN("pen"), RECT("rect"), ARROW("arrow") }
}

data class ReportReceipt(val id: String, val token: String, val ref: String?)

object ReportPayload {
    private fun round(v: Float): Double = (v.toDouble() * 10_000).roundToLong() / 10_000.0

    @JvmStatic
    fun annotations(shapes: List<ReportShape>, width: Int, height: Int): List<Map<String, Any>> {
        if (width <= 0 || height <= 0) return emptyList()
        val w = width.toFloat()
        val h = height.toFloat()
        return shapes.mapNotNull { shape ->
            val first = shape.points.firstOrNull() ?: return@mapNotNull null
            val last = shape.points.last()
            val out = linkedMapOf<String, Any>("tool" to shape.tool.id, "color" to shape.color)
            if (shape.tool == ReportShape.Tool.PEN) {
                val xs = shape.points.map { it[0] }
                val ys = shape.points.map { it[1] }
                out["x"] = round(xs.min() / w)
                out["y"] = round(ys.min() / h)
                out["w"] = round((xs.max() - xs.min()) / w)
                out["h"] = round((ys.max() - ys.min()) / h)
                out["points"] = shape.points.flatMap { listOf(round(it[0] / w), round(it[1] / h)) }
            } else {
                out["x"] = round(first[0] / w)
                out["y"] = round(first[1] / h)
                out["w"] = round((last[0] - first[0]) / w)
                out["h"] = round((last[1] - first[1]) / h)
            }
            out
        }
    }

    @JvmStatic
    fun feedback(
        title: String,
        message: String,
        email: String?,
        context: Map<String, Any?>,
        shapes: List<ReportShape>,
        width: Int,
        height: Int,
        screenshots: Int,
        recordings: Int,
        locale: String,
        replayId: String? = null,
    ): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>(
            "message" to message,
            "kind" to "bug",
            "source" to "widget",
            "locale" to locale,
            "context" to context,
            "attachments" to mapOf("screenshots" to screenshots, "recordings" to recordings),
        )
        title.trim().takeIf { it.isNotEmpty() }?.let { body["title"] = it }
        email?.trim()?.takeIf { it.isNotEmpty() }?.let { body["email"] = it }
        replayId?.takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }?.let { body["replay_id"] = it }
        val list = annotations(shapes, width, height)
        if (list.isNotEmpty()) body["annotations"] = listOf(mapOf("position" to 0, "shapes" to list))
        return body
    }
}

class ReportException(val status: Int) : Exception("Blipit returned $status")

class ReportClient(endpoint: String, val key: String) {
    val endpoint: String = endpoint.trimEnd('/')

    private fun call(url: String, method: String, type: String?, body: ByteArray?, headers: Map<String, String> = emptyMap()): String {
        if (PlatformMismatch.blocked) throw ReportException(409)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("X-Blipit-Key", key)
            type?.let { conn.setRequestProperty("Content-Type", it) }
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (status !in 200..299) {
                PlatformMismatch.check(status, text)
                throw ReportException(status)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun settings(): Pair<Boolean, Int> = try {
        val json = Json.parse(call("$endpoint/api/v1/feedback/settings", "GET", null, null)) as? Map<*, *>
        Pair(json?.get("recording") == true, (json?.get("recording_max_ms") as? Number)?.toInt() ?: 60_000)
    } catch (e: Exception) {
        Pair(false, 60_000)
    }

    fun replaySettings(): ReplayPlan? = try {
        ReplayPlan.fromJson((Json.parse(call("$endpoint/api/v1/feedback/settings", "GET", null, null)) as? Map<*, *>)?.get("replay"))
    } catch (e: Exception) {
        null
    }

    fun stuckSettings(): StuckLimits? = try {
        StuckLimits.fromJson((Json.parse(call("$endpoint/api/v1/feedback/settings", "GET", null, null)) as? Map<*, *>)?.get("stuck"))
    } catch (e: Exception) {
        null
    }

    fun send(body: Map<String, Any?>): ReportReceipt {
        val json = Json.parse(call("$endpoint/api/v1/feedback", "POST", "application/json", Json.encode(body).toByteArray())) as? Map<*, *>
        val token = json?.get("upload_token") as? String ?: throw ReportException(0)
        val id = when (val raw = json["id"]) {
            is Number -> raw.toLong().toString()
            else -> raw.toString()
        }
        return ReportReceipt(id, token, json["ref"] as? String)
    }

    fun uploadScreenshot(receipt: ReportReceipt, data: ByteArray, type: String) {
        call("$endpoint/api/v1/feedback/${receipt.id}/screenshot?position=0", "POST", type, data, mapOf("X-Blipit-Upload" to receipt.token))
    }

    fun uploadRecording(receipt: ReportReceipt, file: File, durationMs: Int) {
        val data = file.readBytes()
        val auth = mapOf("X-Blipit-Upload" to receipt.token)
        val start = Json.parse(
            call(
                "$endpoint/api/v1/feedback/${receipt.id}/recording/start",
                "POST",
                "application/json",
                Json.encode(mapOf("content_type" to "video/mp4", "size" to data.size, "duration_ms" to maxOf(1, durationMs), "position" to 0)).toByteArray(),
                auth,
            ),
        ) as? Map<*, *> ?: throw ReportException(0)
        val url = start["url"] as? String ?: throw ReportException(0)
        val headers = (start["headers"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
        call(url, "PUT", headers["Content-Type"] ?: "video/mp4", data, headers - "Content-Type")
        call("$endpoint/api/v1/feedback/${receipt.id}/recording/complete?position=0", "POST", "application/json", "{}".toByteArray(), auth)
    }
}

internal object ReportLanguage {
    fun pick(tags: List<String> = listOf(Locale.getDefault().toLanguageTag())): String {
        for (tag in tags) {
            val base = tag.lowercase(Locale.ROOT).split('-', '_').first()
            if (ReportStrings.table.containsKey(base)) return base
        }
        return "en"
    }

    fun text(key: String, lang: String = pick()): String = ReportStrings.table[lang]?.get(key) ?: ReportStrings.table["en"]?.get(key) ?: key
}
