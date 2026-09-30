package app.jianxia.core

import app.jianxia.core.cache.TextCache
import app.jianxia.core.crash.BootPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BootAndCacheTest {
    @Test
    fun twoUnfinishedBootsEnterSafeMode() {
        assertEquals(0, BootPolicy.nextCount("", 0))
        assertFalse(BootPolicy.safeMode(0))
        assertEquals(1, BootPolicy.nextCount(BootPolicy.MARKER_BOOTING, 0))
        assertFalse(BootPolicy.safeMode(1))
        val second = BootPolicy.nextCount(BootPolicy.MARKER_BOOTING, 1)
        assertEquals(2, second)
        assertTrue(BootPolicy.safeMode(second))
        assertEquals(0, BootPolicy.nextCount(BootPolicy.MARKER_READY, 0))
    }

    @Test
    fun brokenCacheIsDropped() {
        val dir = Files.createTempDirectory("browse-cache").toFile()
        val cache = TextCache(dir)
        cache.write("home", "{")
        assertEquals("{", cache.read("home"))
        val file = dir.listFiles()?.first { it.name.endsWith(".json") }!!
        file.writeText("")
        val fresh = TextCache(dir)
        assertNull(fresh.read("home"))
        assertFalse(file.exists())
    }
}
