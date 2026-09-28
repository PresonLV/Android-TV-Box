package app.jianxia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.jianxia.tv.ui.push.directPlay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.jianxia.tv.ui.detail.DetailScreen
import app.jianxia.tv.ui.douban.DoubanScreen
import app.jianxia.tv.ui.home.HomeScreen
import app.jianxia.tv.ui.library.LibraryScreen
import app.jianxia.tv.ui.live.LiveScreen
import app.jianxia.tv.ui.player.PlayerScreen
import app.jianxia.tv.ui.push.PushScreen
import app.jianxia.tv.ui.search.SearchScreen
import app.jianxia.tv.ui.settings.SettingsScreen

@Composable
fun AppRoot() {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    val nav = rememberNavController()
    val context = LocalContext.current
    val start = if (settings.startupPage == "live") "live" else "home"
    LaunchedEffect(Unit) {
        val url = (context as? Activity)?.intent?.getStringExtra("pushUrl").orEmpty().trim()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            app.session.request = directPlay(url)
            nav.navigate("player")
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val hideWallpaper = route.startsWith("home") || route.startsWith("detail") || route.isEmpty() || route.startsWith("player")
    Box(Modifier.fillMaxSize().background(palette.bg)) {
        if (!hideWallpaper) WallpaperLayer(settings, Modifier.fillMaxSize())
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("home") {
                HomeScreen(
                    onOpen = { nav.navigate("detail/${navKey(it)}") },
                    onPlay = { nav.navigate("player") },
                    onSettings = { nav.navigate("settings") },
                    onDouban = { nav.navigate("douban") },
                    onBrowseSite = { nav.navigate("discover/${navKey(it)}") },
                    onSearch = { query ->
                        app.session.pendingSearch = query
                        nav.navigate("search")
                    },
                    onHistory = { nav.navigate("history") },
                    onLive = { nav.navigate("live") },
                    onFavorites = { nav.navigate("favorites") },
                    onPush = { nav.navigate("push") },
                    onSearchPage = { nav.navigate("search") },
                )
            }
            composable("search") { SearchScreen(onOpen = { nav.navigate("detail/${navKey(it)}") }) }
            composable("douban") {
                DoubanScreen(
                    onOpen = { nav.navigate("detail/${navKey(it)}") },
                    onSearch = { query ->
                        app.session.pendingSearch = query
                        nav.navigate("search")
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("discover/{key}") { back ->
                DoubanScreen(
                    siteKey = fromNav(back.arguments?.getString("key").orEmpty()),
                    onOpen = { nav.navigate("detail/${navKey(it)}") },
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
            composable("push") { PushScreen(onBack = { nav.popBackStack() }, onPlay = { nav.navigate("player") }) }
            composable("detail/{key}") { back ->
                DetailScreen(
                    encodedKey = back.arguments?.getString("key").orEmpty(),
                    onPlay = { nav.navigate("player") },
                    onBack = { nav.popBackStack() },
                    onSearch = { query ->
                        app.session.pendingSearch = query
                        nav.navigate("search")
                    },
                )
            }
            composable("player") { PlayerScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
