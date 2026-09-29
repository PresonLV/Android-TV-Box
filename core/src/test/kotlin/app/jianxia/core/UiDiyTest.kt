package app.jianxia.core

import app.jianxia.core.backup.BackupCodec
import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.BackupBundle
import app.jianxia.core.model.ShelfToggle
import app.jianxia.core.model.UiDiy
import app.jianxia.core.model.resetSection
import app.jianxia.core.model.shifted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDiyTest {
    @Test
    fun defaultsStayWarehouseAndKeepEveryBadge() {
        val fresh = AppSettings().sanitized()
        assertEquals("warehouse", fresh.homeShell)
        assertEquals("left", fresh.homeRail)
        assertEquals(5, fresh.posterColumns)
        assertEquals(72, fresh.tileAlpha)
        assertEquals(12, fresh.cornerRadius)
        assertEquals("full", fresh.playerBar)
        assertTrue(fresh.showClock)
        assertTrue(fresh.showRating && fresh.showYear && fresh.showQuality && fresh.showDoubanBadge)
        assertEquals(listOf("history", "live", "search", "push", "favorite", "settings"), fresh.homeActions.map { it.id })
        assertTrue(fresh.homeActions.all { it.visible })
        assertTrue(fresh.homeTabs.isEmpty())
    }

    @Test
    fun readOnlyOrderSurvivesSanitizeAndUnknownIdsDrop() {
        val saved = AppSettings(
            homeShell = "nope",
            playerBar = "nope",
            posterColumns = 9,
            tileAlpha = 1,
            cornerRadius = 80,
            homeActions = listOf(
                ShelfToggle("settings", "设置", false),
                ShelfToggle("history", "历史", true),
                ShelfToggle("ghost", "不存在", true),
            ),
            homeTabs = listOf(
                ShelfToggle("  ", "空"),
                ShelfToggle("movie", "电影", false),
                ShelfToggle("movie", "重复", true),
            ),
        ).sanitized()
        assertEquals("warehouse", saved.homeShell)
        assertEquals("full", saved.playerBar)
        assertEquals(5, saved.posterColumns)
        assertEquals(30, saved.tileAlpha)
        assertEquals(28, saved.cornerRadius)
        assertEquals(listOf("settings", "history", "live", "search", "push", "favorite"), saved.homeActions.map { it.id })
        assertFalse(saved.homeActions.first().visible)
        assertEquals(listOf("movie"), saved.homeTabs.map { it.id })
        assertFalse(saved.homeTabs.single().visible)
    }

    @Test
    fun hiddenCategoryStaysHiddenWhenNewClassesArrive() {
        val saved = listOf(
            ShelfToggle("tv", "剧集", false),
            ShelfToggle("home", "主页", true),
        )
        val merged = UiDiy.mergeTabs(saved, listOf("movie" to "电影", "tv" to "电视剧"))
        assertEquals(listOf("tv", "home", "movie"), merged.map { it.id })
        assertEquals("电视剧", merged.first().title)
        assertFalse(merged.first().visible)
        assertEquals(listOf("home", "movie"), UiDiy.visibleTabs(saved, listOf("movie" to "电影", "tv" to "电视剧")).map { it.id })
        assertEquals(listOf("home"), UiDiy.visibleTabs(listOf(ShelfToggle("home", "主页", false)), emptyList()).map { it.id })
    }

    @Test
    fun shiftDoesNotMovePastTheEnds() {
        val rows = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), rows.shifted(0, 1))
        assertEquals(rows, rows.shifted(0, -1))
        assertEquals(rows, rows.shifted(3, 1))
    }

    @Test
    fun backupRoundTripKeepsDiyAndOldFilesStillOpen() {
        val custom = AppSettings(
            accent = "#6FCFC0",
            homeShell = "cinema",
            playerBar = "slim",
            posterColumns = 4,
            posterSize = "large",
            tileAlpha = 40,
            cornerRadius = 4,
            showRating = false,
            showDoubanBadge = false,
            showClock = false,
            fontScale = "large",
            homeActions = listOf(ShelfToggle("search", "搜索", false)),
            homeTabs = listOf(ShelfToggle("home", "主页", true), ShelfToggle("movie", "电影", false)),
        ).sanitized()
        val decoded = BackupCodec.decode(BackupCodec.encode(BackupBundle(settings = custom)))
        assertEquals("cinema", decoded.settings.homeShell)
        assertEquals("slim", decoded.settings.playerBar)
        assertEquals(4, decoded.settings.posterColumns)
        assertEquals("large", decoded.settings.posterSize)
        assertEquals(40, decoded.settings.tileAlpha)
        assertEquals(4, decoded.settings.cornerRadius)
        assertFalse(decoded.settings.showRating)
        assertFalse(decoded.settings.showDoubanBadge)
        assertFalse(decoded.settings.showClock)
        assertEquals("large", decoded.settings.fontScale)
        assertFalse(decoded.settings.homeActions.first { it.id == "search" }.visible)
        assertFalse(decoded.settings.homeTabs.first { it.id == "movie" }.visible)
        val legacy = BackupCodec.decode("""{"version":1,"settings":{"themeMode":"light"},"sources":[]}""")
        assertEquals("light", legacy.settings.themeMode)
        assertEquals("warehouse", legacy.settings.homeShell)
        assertEquals(5, legacy.settings.posterColumns)
        assertTrue(legacy.settings.showClock)
        val reset = custom.resetSection("diy").sanitized()
        assertEquals("warehouse", reset.homeShell)
        assertEquals("left", reset.homeRail)
        assertEquals("full", reset.playerBar)
        assertEquals(5, reset.posterColumns)
        assertTrue(reset.showClock && reset.showRating && reset.showDoubanBadge)
        assertTrue(reset.homeTabs.isEmpty())
        assertTrue(reset.homeActions.all { it.visible })
        assertEquals("#E2B15A", reset.accent)
        val withImage = custom.copy(wallpaperPayload = "abc").sanitized()
        val imageBundle = BackupCodec.decode(BackupCodec.encode(BackupBundle(settings = withImage)))
        assertEquals("abc", imageBundle.settings.wallpaperPayload)
    }
}
