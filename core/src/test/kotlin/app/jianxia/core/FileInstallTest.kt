package app.jianxia.core

import app.jianxia.core.spider.FileInstall
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileInstallTest {
    @Test
    fun replacesReadOnlyNativeLibraryWithoutReopeningIt() {
        val dir = File.createTempFile("libs", "").apply {
            delete()
            mkdirs()
        }
        val existing = File(dir, "ftyguard_v7.so")
        existing.writeBytes("stale".toByteArray())
        existing.setReadOnly()
        assertFalse(existing.canWrite())

        val placed = FileInstall.place(
            directory = dir,
            name = "ftyguard_v7.so",
            bytes = "fresh-so".toByteArray(),
            executable = true,
            readOnly = false,
        )

        assertEquals(existing.absolutePath, placed.absolutePath)
        assertEquals("fresh-so", placed.readText())
        assertTrue(placed.canRead())
        assertTrue(placed.canWrite())
        assertTrue(placed.canExecute())
    }

    @Test
    fun writesANewNameWhenTheOldFileCannotBeDeleted() {
        val dir = File.createTempFile("libs", "").apply {
            delete()
            mkdirs()
        }
        val blocker = File(dir, "ftyguard_v7.so")
        blocker.mkdirs()
        File(blocker, "locked").writeText("x")
        assertFalse(blocker.delete())

        val placed = FileInstall.place(
            directory = dir,
            name = "ftyguard_v7.so",
            bytes = "moved".toByteArray(),
            executable = true,
            readOnly = false,
        )

        assertNotEquals(blocker.absolutePath, placed.absolutePath)
        assertEquals("moved", placed.readText())
        assertTrue(blocker.exists())
    }

    @Test
    fun readOnlyJarCanBeReplacedOnTheNextInstall() {
        val dir = File.createTempFile("jars", "").apply {
            delete()
            mkdirs()
        }
        val first = FileInstall.place(dir, "spider.jar", "one".toByteArray(), executable = false, readOnly = true)
        assertFalse(first.canWrite())
        val second = FileInstall.place(dir, "spider.jar", "two".toByteArray(), executable = false, readOnly = true)
        assertEquals("two", second.readText())
        assertFalse(second.canWrite())
    }
}
