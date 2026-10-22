package io.blipit.android

import android.os.SystemClock
import io.blipit.nuggets.Nuggets

internal object Traces {
    fun load(screen: String, at: LongArray) {
        val n = Core.client ?: return
        val ms = SystemClock.elapsedRealtime() - at[1]
        n.outbox.add("/v1/traces", span(screen, at[0], at[0] + ms, n.config.release, n.config.environment, Core.options.tracesSampleRate))
    }

    @JvmStatic
    fun span(screen: String, startMs: Long, endMs: Long, release: String?, environment: String?, rate: Double): Map<String, Any?> {
        fun attr(key: String, value: Any): Map<String, Any> = mapOf(
            "key" to key,
            "value" to when (value) {
                is Double -> mapOf("doubleValue" to value)
                is Number -> mapOf("intValue" to value)
                else -> mapOf("stringValue" to value.toString())
            },
        )
        val resource = listOfNotNull(
            attr("service.name", release?.substringBefore('@')?.takeIf { it.isNotEmpty() } ?: "android"),
            release?.let { attr("service.version", it) },
            attr("deployment.environment", environment ?: "production"),
            attr("telemetry.sdk.name", Blipit.SDK_NAME),
        )
        val span = mapOf(
            "traceId" to Nuggets.id(),
            "spanId" to Nuggets.id().substring(0, 16),
            "name" to screen,
            "kind" to 1,
            "startTimeUnixNano" to "${startMs}000000",
            "endTimeUnixNano" to "${endMs}000000",
            "attributes" to listOf(attr("blipit.op", "ui.load"), attr("blipit.sample_rate", rate)),
            "status" to mapOf("code" to 1),
        )
        return mapOf(
            "resourceSpans" to listOf(
                mapOf(
                    "resource" to mapOf("attributes" to resource),
                    "scopeSpans" to listOf(mapOf("scope" to mapOf("name" to Blipit.SDK_NAME), "spans" to listOf(span))),
                ),
            ),
        )
    }
}
