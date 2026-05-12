package com.curiokid.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.curiokid.app.ui.chat.ChatScreen
import com.curiokid.app.ui.history.HistoryScreen
import com.curiokid.app.ui.parent.ParentScreen
import com.curiokid.app.ui.settings.SettingsScreen

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val PARENT = "parent"
}

@Composable
fun CurioNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.CHAT,
    ) {
        composable(Routes.CHAT) {
            ChatScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenParent = { navController.navigate(Routes.PARENT) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PARENT) {
            ParentScreen(onBack = { navController.popBackStack() })
        }
    }
}
