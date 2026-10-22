package io.blipit.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCrashTest {
    private val report = """
        signal 11
        code 1
        addr 0x0000000000000000
        tid 4242
        thread RenderThread
        reg x0 0x0000000000000001
        reg pc 0x0000007a00001234
        pc 0x0000007a00001234
        frame 0x0000007a00001234
        frame 0x0000007b00000010
        frame 0x0000007a00001234
        frame 0x0000007a00002000
        frame 0x0000007c00000100
        maps
        7a00000000-7a00010000 r-xp 00001000 fd:00 123 /data/app/~~x/com.example-1/lib/arm64/libgame.so
        7b00000000-7b00010000 r-xp 00000000 fd:00 124 /apex/com.android.runtime/lib64/bionic/libc.so
        7d00000000-7d00010000 rw-p 00000000 00:00 0 [anon:stack]
    """.trimIndent()

    @Test
    fun aSignalReportBecomesFramesOldestFirst() {
        val parsed = NativeCrash.parse(report, "/data/app/~~x/com.example-1/lib/arm64")!!
        assertEquals("SIGSEGV", parsed.signal)
        assertEquals(1, parsed.code)
        assertEquals("RenderThread", parsed.thread)
        assertEquals(4242L, parsed.tid)
        assertEquals("0x0000000000000001", parsed.registers["x0"])
        assertEquals(3, parsed.frames.size)
        val crash = parsed.frames.last()
        assertEquals("libgame.so", crash["filename"])
        assertEquals("0x2234", crash["function"])
        assertEquals(true, crash["in_app"])
        assertEquals("unknown", parsed.frames.first()["filename"])
        assertEquals(false, parsed.frames.first()["in_app"])
        assertEquals(listOf("/data/app/~~x/com.example-1/lib/arm64/libgame.so"), parsed.libraries)
    }

    @Test
    fun handlerFramesBeforeTheCrashSiteAreDropped() {
        val parsed = NativeCrash.parse(report, null)!!
        assertTrue(parsed.frames.none { it["filename"] == "libc.so" })
    }

    @Test
    fun garbageIsIgnored() {
        assertNull(NativeCrash.parse("not a report", null))
        assertEquals("SIG99", NativeCrash.parse("signal 99\nmaps\n", null)!!.signal)
    }
}
