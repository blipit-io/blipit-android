package io.blipit.android

internal object PlatformMismatch {
    @Volatile
    var blocked = false
        private set

    fun check(status: Int, body: String?): Boolean {
        if (status != 409) return false
        val json = runCatching { Json.parse(body ?: "") as? Map<*, *> }.getOrNull() ?: return false
        if (json["reason"] != "platform_mismatch") return false
        synchronized(this) {
            if (blocked) return true
            blocked = true
        }
        val message = json["error"] as? String ?: "This key belongs to a Blipit project for another platform."
        runCatching { android.util.Log.e("Blipit", "BLIPIT IS NOT SENDING: $message Nothing more is sent with this key until the app restarts.") }
        return true
    }

    internal fun reset() {
        blocked = false
    }
}
