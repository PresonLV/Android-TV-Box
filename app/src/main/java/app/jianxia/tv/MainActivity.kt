package app.jianxia.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jianxia.tv.ui.AppRoot
import app.jianxia.tv.ui.JianXiaTheme
import app.jianxia.tv.ui.LocalApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as JianXiaApp).container
        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            JianXiaTheme(settings) {
                CompositionLocalProvider(LocalApp provides container) {
                    AppRoot()
                }
            }
        }
    }
}
