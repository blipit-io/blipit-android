package io.blipit.android

import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    @Test
    fun annotationsAreNormalisedLikeTheBrowserReporter() {
        val list = ReportPayload.annotations(
            listOf(
                ReportShape(ReportShape.Tool.ARROW, mutableListOf(floatArrayOf(20f, 10f), floatArrayOf(120f, 60f))),
                ReportShape(ReportShape.Tool.PEN, mutableListOf(floatArrayOf(50f, 50f), floatArrayOf(100f, 25f), floatArrayOf(150f, 75f))),
            ),
            200,
            100,
        )
        assertEquals(mapOf("tool" to "arrow", "color" to "#FF3B30", "x" to 0.1, "y" to 0.1, "w" to 0.5, "h" to 0.5), list[0])
        assertEquals(listOf(0.25, 0.5, 0.5, 0.25, 0.75, 0.75), list[1]["points"])
        assertTrue(ReportPayload.annotations(emptyList(), 0, 0).isEmpty())
    }

    @Test
    fun jsonRoundTrips() {
        val text = Json.encode(mapOf("a" to "x\"y\n", "b" to listOf(1, 2.5, true, null), "c" to mapOf("d" to "é")))
        assertEquals("""{"a":"x\"y\n","b":[1,2.5,true,null],"c":{"d":"é"}}""", text)
        assertEquals(mapOf("a" to "x\"y\n", "b" to listOf(1L, 2.5, true, null), "c" to mapOf("d" to "é")), Json.parse(text))
        assertEquals(mapOf("u" to "A"), Json.parse("""{ "u" : "A" }"""))
    }

    @Test
    fun stringsCoverElevenLanguages() {
        assertEquals(11, ReportStrings.table.size)
        val keys = ReportStrings.table.getValue("en").keys
        ReportStrings.table.forEach { (lang, strings) -> assertEquals(lang, keys, strings.keys) }
        assertEquals("ar", ReportLanguage.pick(listOf("ar-AE")))
        assertEquals("en", ReportLanguage.pick(listOf("xx")))
        assertNotEquals(ReportLanguage.text("send", "ja"), ReportLanguage.text("send", "en"))
    }

    @Test
    fun clientSendsTheReportThenUploads() {
        val seen = CopyOnWriteArrayList<Triple<String, Map<String, String>, ByteArray>>()
        val server = ServerSocket(0)
        val port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { sock ->
                    val input = sock.getInputStream().buffered()
                    fun line(): String {
                        val out = StringBuilder()
                        while (true) {
                            val c = input.read()
                            if (c < 0 || c == '\n'.code) break
                            if (c != '\r'.code) out.append(c.toChar())
                        }
                        return out.toString()
                    }
                    val (method, path) = line().split(" ").let { it[0] to it[1] }
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        val i = h.indexOf(':')
                        headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) read += input.read(body, read, length - read)
                    seen.add(Triple("$method $path", headers, body))
                    val reply = when {
                        path == "/api/v1/feedback" -> """{"id":42,"ref":"BR-9","upload_token":"tok"}"""
                        path.endsWith("/recording/start") -> """{"url":"http://127.0.0.1:$port/api/v1/feedback/42/recording/0","headers":{"Content-Type":"video/mp4","X-Blipit-Upload":"tok"}}"""
                        path == "/api/v1/feedback/settings" -> """{"recording":false,"recording_max_ms":30000}"""
                        else -> "{}"
                    }.toByteArray()
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 ${if (method == "GET") 200 else 201} OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(reply)
                    out.flush()
                }
            }
        }
        try {
            val client = ReportClient("http://127.0.0.1:$port/", "blipit_pk_abc")
            assertEquals(Pair(false, 30000), client.settings())
            val receipt = client.send(ReportPayload.feedback(" ", "Totals wrong", null, mapOf("platform" to "android"), emptyList(), 0, 0, 1, 1, "en"))
            assertEquals(ReportReceipt("42", "tok", "BR-9"), receipt)
            client.uploadScreenshot(receipt, byteArrayOf(1, 2, 3), "image/jpeg")
            val file = File.createTempFile("blipit", ".mp4").apply { writeBytes(ByteArray(2048) { 1 }) }
            client.uploadRecording(receipt, file, 4200)
            val calls = seen.map { it.first }
            assertEquals(listOf("GET /api/v1/feedback/settings", "POST /api/v1/feedback", "POST /api/v1/feedback/42/screenshot?position=0", "POST /api/v1/feedback/42/recording/start", "PUT /api/v1/feedback/42/recording/0", "POST /api/v1/feedback/42/recording/complete?position=0"), calls)
            val post = Json.parse(String(seen[1].third)) as Map<*, *>
            assertNull(post["title"])
            assertEquals("widget", post["source"])
            val shot = seen[2]
            assertEquals("blipit_pk_abc", shot.second["x-blipit-key"])
            assertEquals("tok", shot.second["x-blipit-upload"])
            assertEquals("image/jpeg", shot.second["content-type"])
            assertEquals(2048, seen[4].third.size)
            assertEquals("tok", seen[4].second["x-blipit-upload"])
        } finally {
            server.close()
        }
    }
}
