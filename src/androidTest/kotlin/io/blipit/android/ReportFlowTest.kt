package io.blipit.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class HostActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(242, 245, 250))
        }
        column.addView(TextView(this).apply {
            text = "Checkout"
            textSize = 28f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(37, 99, 235))
            gravity = Gravity.BOTTOM
            setPadding(48, 160, 48, 40)
        }, LinearLayout.LayoutParams(-1, -2))
        repeat(5) { i ->
            column.addView(TextView(this).apply {
                text = "Item ${i + 1}    AED ${(i + 1) * 120}.00"
                textSize = 18f
                setTextColor(Color.rgb(11, 26, 58))
                setBackgroundColor(Color.WHITE)
                setPadding(48, 60, 48, 60)
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(40, 30, 40, 0) })
        }
        setContentView(column)
    }
}

@RunWith(AndroidJUnit4::class)
class ReportFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val seen = CopyOnWriteArrayList<Triple<String, Map<String, String>, ByteArray>>()
    private val shots = File(instrumentation.context.getExternalFilesDir(null), "screens").apply { mkdirs() }

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
                    seen.add(Triple("${parts[0]} ${parts[1]}", headers, body))
                    val reply = when (parts[1]) {
                        "/api/v1/feedback" -> """{"id":42,"ref":"BR-12","upload_token":"tok"}"""
                        "/api/v1/feedback/settings" -> """{"recording":true,"recording_max_ms":60000}"""
                        else -> "{}"
                    }.toByteArray()
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 ${if (parts[0] == "GET") 200 else 201} OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(reply)
                    out.flush()
                }
            }
        }
        return server.localPort
    }

    private fun shot(name: String) {
        SystemClock.sleep(700)
        instrumentation.waitForIdleSync()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun resumed(): Activity? {
        val read = { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return read()
        var found: Activity? = null
        instrumentation.runOnMainSync { found = read() }
        return found
    }

    private fun waitFor(timeout: Long = 8000, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            instrumentation.runOnMainSync { ok = check() }
            if (ok) return
            SystemClock.sleep(100)
        }
        throw AssertionError("timed out")
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun report(): BlipitReportActivity = resumed() as BlipitReportActivity

    private fun click(match: (View) -> Boolean) {
        val activity = report()
        instrumentation.runOnMainSync { val view = find(activity.window.decorView, match); assertNotNull(view); view!!.performClick() }
        instrumentation.waitForIdleSync()
    }

    private fun touch(view: View, vararg steps: Pair<Int, List<FloatArray>>) {
        val down = SystemClock.uptimeMillis()
        steps.forEachIndexed { n, (action, points) ->
            val props = Array(points.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(points.size) { MotionEvent.PointerCoords().apply { x = points[it][0]; y = points[it][1]; pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(down, down + n * 16L, action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            instrumentation.runOnMainSync { view.dispatchTouchEvent(event) }
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun screenshotIsMarkedWhileZoomedAndSent() {
        val port = serve()
        val app = instrumentation.targetContext.applicationContext
        instrumentation.runOnMainSync {
            Blipit.init(app, BlipitOptions().apply { key = "blipit_pk_test"; project = "7"; endpoint = "http://127.0.0.1:$port"; startupDeadlineMs = 0 })
        }
        ActivityScenario.launch(HostActivity::class.java).use {
            Blipit.showReportButton()
            waitFor { resumed()?.window?.decorView?.let { d -> find(d) { v -> v.tag == "blipit-report-button" } } != null }
            shot("01-app-with-button")

            Blipit.showReport()
            waitFor { resumed() is BlipitReportActivity }
            shot("02-choice")
            val lang = report().lang
            val t = { key: String -> ReportLanguage.text(key, lang) }

            click { it.contentDescription == t("mShot") }
            waitFor { (resumed() as? BlipitReportActivity)?.stage == "editor" }
            shot("03-editor")

            val editor = report().editor!!
            val w = editor.width.toFloat()
            val h = editor.height.toFloat()
            touch(editor, MotionEvent.ACTION_DOWN to listOf(floatArrayOf(w * 0.2f, h * 0.3f)), MotionEvent.ACTION_MOVE to listOf(floatArrayOf(w * 0.4f, h * 0.35f)), MotionEvent.ACTION_MOVE to listOf(floatArrayOf(w * 0.6f, h * 0.3f)), MotionEvent.ACTION_UP to listOf(floatArrayOf(w * 0.6f, h * 0.3f)))
            assertEquals(1, report().shapes.size)

            val a = floatArrayOf(w * 0.45f, h * 0.5f)
            val b = floatArrayOf(w * 0.55f, h * 0.5f)
            touch(
                editor,
                MotionEvent.ACTION_DOWN to listOf(a),
                (MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)) to listOf(a, b),
                MotionEvent.ACTION_MOVE to listOf(floatArrayOf(w * 0.35f, h * 0.5f), floatArrayOf(w * 0.65f, h * 0.5f)),
                MotionEvent.ACTION_MOVE to listOf(floatArrayOf(w * 0.2f, h * 0.5f), floatArrayOf(w * 0.8f, h * 0.5f)),
                (MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)) to listOf(floatArrayOf(w * 0.2f, h * 0.5f), floatArrayOf(w * 0.8f, h * 0.5f)),
                MotionEvent.ACTION_UP to listOf(floatArrayOf(w * 0.2f, h * 0.5f)),
            )
            assertEquals("pinch adds no mark", 1, report().shapes.size)
            val centre = editor.toImage(w / 2, h / 2)
            val corner = editor.toImage(0f, 0f)
            assertTrue("zoomed in", centre[0] - corner[0] < report().image!!.width / 2f * 0.8f)

            click { it is TextView && it.contentDescription == t("rect") }
            touch(editor, MotionEvent.ACTION_DOWN to listOf(floatArrayOf(w * 0.3f, h * 0.4f)), MotionEvent.ACTION_MOVE to listOf(floatArrayOf(w * 0.7f, h * 0.6f)), MotionEvent.ACTION_UP to listOf(floatArrayOf(w * 0.7f, h * 0.6f)))
            assertEquals(2, report().shapes.size)
            val rect = report().shapes[1].points
            val expected = editor.toImage(w * 0.3f, h * 0.4f)
            assertEquals(expected[0], rect.first()[0], 1f)
            shot("04-editor-zoomed-marked")

            click { it is TextView && it.text == t("mNext") }
            waitFor { (resumed() as? BlipitReportActivity)?.stage == "details" }
            click { it is TextView && it.text == t("send") }
            waitFor { report().status?.text == t("required") }
            val details = report()
            instrumentation.runOnMainSync {
                (find(details.window.decorView) { it is EditText && it.hint == t("fDesc") } as EditText).setText("The total does not update after removing an item")
                (find(details.window.decorView) { it is EditText && it.hint == t("fTitle") } as EditText).setText("Cart total wrong")
            }
            shot("05-details")
            click { it is TextView && it.text == t("send") }
            waitFor(15000) { seen.any { it.first.contains("/screenshot") } }
            shot("06-sent")
        }

        val post = seen.first { it.first == "POST /api/v1/feedback" }
        assertEquals("blipit_pk_test", post.second["x-blipit-key"])
        val json = Json.parse(String(post.third)) as Map<*, *>
        assertEquals("Cart total wrong", json["title"])
        assertEquals("widget", json["source"])
        assertEquals("android", (json["context"] as Map<*, *>)["platform"])
        val sets = json["annotations"] as List<*>
        assertEquals(2, ((sets[0] as Map<*, *>)["shapes"] as List<*>).size)
        val upload = seen.first { it.first.contains("/screenshot") }
        assertEquals("image/jpeg", upload.second["content-type"])
        assertEquals("tok", upload.second["x-blipit-upload"])
        val decoded = BitmapFactory.decodeByteArray(upload.third, 0, upload.third.size)
        assertNotNull(decoded)
        assertTrue(decoded.width > 300)
    }
}
