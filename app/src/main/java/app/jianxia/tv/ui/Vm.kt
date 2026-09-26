package app.jianxia.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jianxia.tv.AppContainer

@Composable
inline fun <reified T : ViewModel> appViewModel(crossinline create: (AppContainer) -> T): T {
    val app = LocalApp.current
    val factory = remember(app) {
        object : ViewModelProvider.Factory {
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
                @Suppress("UNCHECKED_CAST")
                return create(app) as VM
            }
        }
    }
    return viewModel(factory = factory)
}
