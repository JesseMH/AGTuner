package com.agtuner.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agtuner.ui.screens.SettingsScreen
import com.agtuner.ui.screens.StringConfigurationScreen
import com.agtuner.ui.screens.TunerScreen
import com.agtuner.viewmodel.TunerViewModel
import com.agtuner.widget.WidgetLaunchMode

/**
 * Navigation routes for the app.
 */
object Routes {
    const val TUNER = "tuner"
    const val SETTINGS = "settings"
    const val STRING_CONFIG = "string_config"
}

@Composable
fun TunerNavigation(
    navController: NavHostController = rememberNavController(),
    pendingLaunchMode: WidgetLaunchMode? = null,
    onPendingLaunchModeConsumed: () -> Unit = {},
) {
    // Share the ViewModel across all screens to maintain audio listening state
    val sharedViewModel: TunerViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = Routes.TUNER
    ) {
        composable(Routes.TUNER) {
            TunerScreen(
                onNavigateToSettings = {
                    sharedViewModel.stopListening()
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
                onNavigateToStringConfig = {
                    sharedViewModel.stopListening()
                    navController.navigate(Routes.STRING_CONFIG) { launchSingleTop = true }
                },
                pendingLaunchMode = pendingLaunchMode,
                onPendingLaunchModeConsumed = onPendingLaunchModeConsumed,
                viewModel = sharedViewModel,
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                viewModel = sharedViewModel
            )
        }

        composable(Routes.STRING_CONFIG) {
            StringConfigurationScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                viewModel = sharedViewModel
            )
        }
    }
}
