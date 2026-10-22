package io.blipit.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.blipit.nuggets.Json
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class CrashActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Blipit.init(this, BlipitOptions().apply { key = "blipit_pk_test"; project = "7"; endpoint = "http://127.0.0.1:9" })
        window.decorView.postDelayed({ Process.sendSignal(Process.myPid(), 11) }, 500)
    }
}

@RunWith(AndroidJUnit4::class)
class NuggetsFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val seen = CopyOnWriteArrayList<Pair<String, String>>()

    private fun serve(): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (true) {
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
                    val parts = line().split(" ")
                    var length = 0
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toInt()
                    }
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) read += input.read(body, read, length - read)
                    seen.add("${parts[0]} ${parts[1]}" to String(body))
                    val reply = when (parts[1]) {
                        "/api/v1/feedback/settings" -> """{"replay":{"mode":"sessions","sessionSampleRate":1,"onErrorSampleRate":1,"labels":true}}"""
                        else -> """{"accepted":true}"""
                    }.toByteArray()
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 ${if (parts[0] == "GET") 200 else 202} OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(reply)
                    out.flush()
                }
            }
        }
        return server.localPort
    }

    private fun waitFor(timeout: Long, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return
            SystemClock.sleep(200)
        }
        throw AssertionError("timed out, saw ${seen.map { it.first }}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun events(): List<Map<String, Any?>> = seen.filter { it.first == "POST /api/v1/events" }.map { Json.parse(it.second) as Map<String, Any?> }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun crashesAnrsAndReplayReachBlipit() {
        val app = instrumentation.targetContext.applicationContext
        val dir = File(app.noBackupFilesDir, "blipit")
        dir.deleteRecursively()
        val report = File(dir, "native-crash.txt")
        app.startActivity(Intent(app, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor(15_000) { report.isFile && report.readText().contains("\nmaps\n") }
        SystemClock.sleep(1000)
        val parsed = NativeCrash.parse(report.readText(), app.applicationInfo.nativeLibraryDir)
        assertNotNull(parsed)
        assertEquals("SIGSEGV", parsed!!.signal)
        assertTrue(parsed.frames.isNotEmpty() && parsed.libraries.isNotEmpty())

        val port = serve()
        instrumentation.runOnMainSync {
            Blipit.init(app, BlipitOptions().apply {
                key = "blipit_pk_test"
                project = "7"
                endpoint = "http://127.0.0.1:$port"
                release = "flow@1.0.0"
                environment = "test"
            })
            Blipit.setReplayConsent(true)
        }
        waitFor(10_000) { events().any { it["exception"].let { e -> (e as? Map<*, *>)?.get("type") == "SIGSEGV" } } }
        assertTrue(!report.exists())

        ActivityScenario.launch(HostActivity::class.java).use { scenario ->
            SystemClock.sleep(2500)
            Blipit.addBreadcrumb("opened checkout", "ui")
            Blipit.captureException(IllegalStateException("checkout failed"))
            waitFor(10_000) { events().any { (it["exception"] as? Map<*, *>)?.get("type") == "java.lang.IllegalStateException" } }
            val handled = events().first { (it["exception"] as? Map<*, *>)?.get("type") == "java.lang.IllegalStateException" }
            assertEquals("blipit-android", (handled["sdk"] as Map<String, Any?>)["name"])
            assertEquals("Android", ((handled["contexts"] as Map<String, Any?>)["os"] as Map<String, Any?>)["name"])
            assertTrue(((handled["tags"] as Map<String, Any?>)["replay_id"] as String).matches(Regex("^[0-9a-f]{32}$")))
            assertTrue((handled["breadcrumbs"] as List<Map<String, Any?>>).any { it["category"] == "navigation" && it["message"] == "HostActivity" })
            assertTrue(File(dir, "session.json").isFile)

            scenario.onActivity { }
            instrumentation.runOnMainSync { SystemClock.sleep(6500) }
            waitFor(10_000) { events().any { (it["exception"] as? Map<*, *>)?.get("type") == "ApplicationNotResponding" } }
            val anr = events().first { (it["exception"] as? Map<*, *>)?.get("type") == "ApplicationNotResponding" }
            assertEquals("anr", (anr["tags"] as Map<String, Any?>)["mechanism"])

            waitFor(30_000) { seen.any { it.first.matches(Regex("POST /api/v1/replays/[0-9a-f]{32}/video/0")) } }
            val segment = Json.parse(seen.first { it.first.contains("/video/0") }.second) as Map<String, Any?>
            assertEquals("android", segment["platform"])
            val video = Base64.getDecoder().decode(segment["video"] as String)
            assertEquals("ftyp", String(video, 4, 4, Charsets.ISO_8859_1))
            val types = (segment["events"] as List<Map<String, Any?>>).map { it["type"] }
            assertEquals(listOf(4L, 5L), types.take(2))
            File(instrumentation.context.getExternalFilesDir(null), "replay-0.mp4").writeBytes(video)
        }
    }
}
