package io.blipit.android

import io.sentry.SentryOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BlipitTest {
    @Test
    fun dsnIsBuiltFromKeyAndProject() {
        assertEquals("https://blipit_pk_abc@in.blipit.io/7", Blipit.dsn("blipit_pk_abc", "7"))
        assertEquals("http://k@127.0.0.1:8080/9", Blipit.dsn("k", "9", "http://127.0.0.1:8080/"))
    }

    @Test
    fun applySetsDsnAndOptions() {
        val sentry = SentryOptions()
        Blipit.apply(sentry, BlipitOptions().apply {
            key = "blipit_pk_abc"
            project = "7"
            environment = "test"
            release = "app@1.0.0"
            tracesSampleRate = 0.2
        })
        assertEquals("https://blipit_pk_abc@in.blipit.io/7", sentry.dsn)
        assertEquals("test", sentry.environment)
        assertEquals("app@1.0.0", sentry.release)
        assertEquals(0.2, sentry.tracesSampleRate!!, 0.0)
        assertFalse(sentry.isSendDefaultPii)
    }

    @Test
    fun securityContextDropsEmptyFields() {
        assertEquals(
            mapOf("kind" to "login_failed", "actor" to "ana@example.com", "ip" to "10.0.0.1", "target" to "/login"),
            Blipit.securityContext("login_failed", "ana@example.com", ip = "10.0.0.1", target = "/login"),
        )
    }
}
