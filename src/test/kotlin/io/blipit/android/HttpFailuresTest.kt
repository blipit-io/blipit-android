package io.blipit.android

import io.blipit.nuggets.Config
import io.blipit.nuggets.Nuggets
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFailuresTest {
    private val sent = ArrayList<Map<String, Any?>>()

    private fun nuggets(): Nuggets {
        val config = Config().apply {
            key = "blipit_pk_test"
            endpoint = "http://127.0.0.1:9"
            sdkName = Blipit.SDK_NAME
            sdkVersion = Blipit.VERSION
            hook = Config.Hook { event, _ -> synchronized(sent) { sent.add(LinkedHashMap(event)) } }
        }
        return Nuggets(config).also { Core.client = it }
    }

    @After
    fun reset() {
        Core.client?.close()
        Core.client = null
        HttpFailures.enabled = true
        HttpFailures.intervalMs = 60_000
    }

    @Suppress("UNCHECKED_CAST")
    private fun failures() = synchronized(sent) { sent.filter { (it["exception"] as? Map<String, Any?>)?.get("type") == HttpFailures.TYPE } }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun failedRequestsThroughOkHttpBecomeOneRateLimitedErrorEventEach() {
        nuggets()
        val server = MockWebServer()
        listOf(503, 503, 401, 200).forEach { server.enqueue(MockResponse().setResponseCode(it)) }
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(BlipitInterceptor()).build()
        fun call(path: String, method: String = "GET") {
            val body = if (method == "POST") "{}".toRequestBody() else null
            runCatching { client.newCall(Request.Builder().url(server.url(path)).method(method, body).build()).execute().close() }
        }
        call("/orders/123?token=secret", "POST")
        call("/orders/123", "POST")
        call("/me")
        call("/cart")
        server.shutdown()
        val closed = ServerSocket(0).use { it.localPort }
        runCatching { client.newCall(Request.Builder().url("http://127.0.0.1:$closed/home").build()).execute().close() }

        val events = failures()
        assertEquals("one 503 within the minute and one refused connection; 401 and 200 are not failures", 2, events.size)
        val first = events[0]
        val host = "${server.hostName}:${server.port}"
        val exception = first["exception"] as Map<String, Any?>
        assertEquals("POST ${server.hostName}/orders/123 failed: 503", exception["value"])
        assertEquals(true, exception["handled"])
        assertEquals("error", first["level"])
        assertEquals(mapOf("url" to "http://$host/orders/123", "method" to "POST"), first["request"])
        val http = (first["extra"] as Map<String, Any?>)["http"] as Map<String, Any?>
        assertEquals(503, http["status_code"])
        assertTrue(http["duration_ms"] is Long)
        assertTrue((first["breadcrumbs"] as List<Map<String, Any?>>).any { it["category"] == "http" })
        assertFalse(first.toString().contains("secret"))
        val refused = events[1]
        assertEquals("GET 127.0.0.1/home failed: ConnectException", (refused["exception"] as Map<String, Any?>)["value"])
    }

    @Test
    fun theGateFoldsRepeatsIntoTheNextEvent() {
        val gate = FailureGate()
        assertEquals(0, gate.pass("GET a/b", 0, 60_000))
        assertNull(gate.pass("GET a/b", 1_000, 60_000))
        assertNull(gate.pass("GET a/b", 2_000, 60_000))
        assertEquals(0, gate.pass("GET a/c", 2_000, 60_000))
        assertEquals(2, gate.pass("GET a/b", 61_000, 60_000))
        assertNull(gate.pass("GET a/b", 62_000, 60_000))
    }

    @Test
    fun onlyTransportErrorsAndServerErrorsCount() {
        assertTrue(HttpFailures.failed(500, null, false))
        assertTrue(HttpFailures.failed(503, null, false))
        assertFalse(HttpFailures.failed(404, null, false))
        assertFalse(HttpFailures.failed(403, null, false))
        assertFalse(HttpFailures.failed(200, null, false))
        assertTrue(HttpFailures.failed(null, SocketTimeoutException("timeout"), false))
        assertFalse(HttpFailures.failed(null, java.io.IOException("Canceled"), true))
        assertEquals("ConnectException", HttpFailures.name(ConnectException()))
        assertEquals("https://api.example.com:8443/a/b", HttpFailures.clean("https://user:pw@api.example.com:8443/a/b?token=x#f"))
    }

    @Test
    fun serverSettingsSwitchCaptureOff() {
        val n = nuggets()
        HttpFailures.configure(mapOf("enabled" to false, "interval_ms" to 5_000))
        assertFalse(HttpFailures.enabled)
        assertEquals(5_000L, HttpFailures.intervalMs)
        HttpFailures.configure(null)
        assertFalse(HttpFailures.enabled)
        assertNull(HttpFailures.report(n, "GET", "https://api.example.com/x", 500, null, false, 3))
        assertTrue(failures().isEmpty())
    }
}
