package app.jianxia.tv.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import app.jianxia.tv.ui.detail.DetailScreen
import app.jianxia.tv.ui.home.HomeScreen
import app.jianxia.tv.ui.library.LibraryScreen
import app.jianxia.tv.ui.live.LiveScreen
import app.jianxia.tv.ui.douban.DoubanScreen
import app.jianxia.tv.ui.player.PlayerScreen
import app.jianxia.tv.ui.search.SearchScreen
import app.jianxia.tv.ui.settings.SettingsScreen
import androidx.compose.foundation.BorderStroke

private data class RailItem(val route: String, val label: String, val icon: ImageVector)

private val rail = listOf(
    RailItem("home", "首页", Icons.Filled.Home),
    RailItem("search", "搜索", Icons.Filled.Search),
    RailItem("live", "直播", Icons.Filled.PlayArrow),
    RailItem("favorites", "收藏", Icons.Filled.Favorite),
    RailItem("history", "历史", Icons.AutoMirrored.Filled.List),
    RailItem("settings", "设置", Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    val nav = rememberNavController()
    val start = if (settings.startupPage == "live") "live" else "home"
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val showRail = route != "player" && !route.startsWith("detail")
    val cinema = settings.homeLayout == "cinema"
    val hideWallpaper = cinema && (route.startsWith("home") || route.startsWith("detail") || route.isEmpty())
    Box(Modifier.fillMaxSize().background(palette.bg)) {
        if (!hideWallpaper) WallpaperLayer(settings, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize()) {
            if (showRail && cinema) {
                ProvideCinema {
                    CinemaRail(
                        current = route.substringBefore("/"),
                        reduceMotion = settings.reduceMotion,
                        onSelect = { target ->
                            nav.navigate(target) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            } else if (showRail) {
                Rail(
                    current = route.substringBefore("/"),
                    onSelect = { target ->
                        nav.navigate(target) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            NavHost(
                navController = nav,
                startDestination = start,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                composable("home") {
                    HomeScreen(
                        onOpen = { nav.navigate("detail/${navKey(it)}") },
                        onPlay = { nav.navigate("player") },
                        onSettings = { nav.navigate("settings/add") },
                        onDouban = { nav.navigate("douban") },
                        onSearch = { query ->
                            app.session.pendingSearch = query
                            nav.navigate("search")
                        },
                    )
                }
                composable("search") { SearchScreen(onOpen = { nav.navigate("detail/${navKey(it)}") }) }
                composable("douban") {
                    DoubanScreen(
                        onSearch = { query ->
                            app.session.pendingSearch = query
                            nav.navigate("search")
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("live") { LiveScreen() }
                composable("favorites") { LibraryScreen(favorites = true, onOpen = { nav.navigate("detail/${navKey(it)}") }) }
                composable("history") { LibraryScreen(favorites = false, onOpen = { nav.navigate("detail/${navKey(it)}") }, onPlay = { nav.navigate("player") }) }
                composable("settings") { SettingsScreen() }
                composable("settings/add") { SettingsScreen(start = "sources", openCreate = true) }
                composable("detail/{key}") { back ->
                    DetailScreen(
                        encodedKey = back.arguments?.getString("key").orEmpty(),
                        onPlay = { nav.navigate("player") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("player") { PlayerScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}

private val cinemaRail = listOf(
    RailItem("home", "首页", Icons.Filled.Home),
    RailItem("search", "搜索", Icons.Filled.Search),
    RailItem("live", "直播", Icons.Filled.PlayArrow),
    RailItem("favorites", "收藏", Icons.Filled.Favorite),
    RailItem("history", "历史", Icons.AutoMirrored.Filled.List),
    RailItem("settings", "设置", Icons.Filled.Settings),
)

@Composable
private fun CinemaRail(current: String, reduceMotion: Boolean, onSelect: (String) -> Unit) {
    val palette = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(if (expanded) 188.dp else 76.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.45f))
            .onFocusChanged { expanded = it.hasFocus }
            .then(if (reduceMotion) Modifier else Modifier.animateContentSize(tween(180)))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("简匣", color = palette.accent, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp), maxLines = 1)
        cinemaRail.forEach { item ->
            val selected = current == item.route
            Surface(
                onClick = { onSelect(item.route) },
                modifier = Modifier.padding(vertical = 4.dp).width(if (expanded) 164.dp else 56.dp),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) palette.accent.copy(alpha = 0.2f) else Color.Transparent,
                    contentColor = if (selected) palette.accent else palette.text,
                    focusedContainerColor = palette.surface2,
                    focusedContentColor = palette.accent,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = if (reduceMotion) 1f else 1.04f),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(16.dp)),
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp), tint = if (selected) palette.accent else palette.text)
                    if (expanded) {
                        Text(item.label, color = if (selected) palette.accent else palette.text, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Rail(current: String, onSelect: (String) -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.width(108.dp).fillMaxHeight().background(palette.bg.copy(alpha = 0.35f)).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("简匣", color = palette.accent, fontSize = 18.sp, modifier = Modifier.padding(bottom = 18.dp))
        rail.forEach { item ->
            val selected = current == item.route
            Surface(
                onClick = { onSelect(item.route) },
                modifier = Modifier.padding(vertical = 4.dp).width(84.dp),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) palette.accent.copy(alpha = 0.18f) else palette.surface.copy(alpha = 0.2f),
                    contentColor = if (selected) palette.accent else palette.text,
                    focusedContainerColor = palette.surface2,
                    focusedContentColor = palette.accent,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(16.dp)),
                    focusedDisabledBorder = Border.None,
                ),
            ) {
                Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp), tint = if (selected) palette.accent else palette.text)
                    Text(item.label, color = if (selected) palette.accent else palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
