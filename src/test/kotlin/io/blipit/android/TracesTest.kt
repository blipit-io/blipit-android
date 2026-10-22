package io.blipit.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TracesTest {
    @Test
    @Suppress("UNCHECKED_CAST")
    fun activityLoadsAreOtlpSpans() {
        val body = Traces.span("CheckoutActivity", 1_700_000_000_000L, 1_700_000_000_350L, "com.example@1.2.0+12", null, 0.2)
        val rs = (body["resourceSpans"] as List<Map<String, Any?>>)[0]
        val span = ((rs["scopeSpans"] as List<Map<String, Any?>>)[0]["spans"] as List<Map<String, Any?>>)[0]
        assertEquals("CheckoutActivity", span["name"])
        assertEquals("1700000000000000000", span["startTimeUnixNano"])
        assertEquals("1700000000350000000", span["endTimeUnixNano"])
        assertTrue((span["traceId"] as String).matches(Regex("^[0-9a-f]{32}$")))
        assertTrue((span["spanId"] as String).matches(Regex("^[0-9a-f]{16}$")))
        val resource = (rs["resource"] as Map<String, Any?>)["attributes"] as List<Map<String, Any?>>
        assertEquals(mapOf("key" to "service.name", "value" to mapOf("stringValue" to "com.example")), resource[0])
    }
}
