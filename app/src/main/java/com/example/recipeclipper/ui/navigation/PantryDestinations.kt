package com.example.recipeclipper.ui.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.example.recipeclipper.data.flags.pantrySettingsLink
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.pantry.PantryScreen
import com.example.recipeclipper.ui.settings.SettingsSection
import com.example.recipeclipper.ui.settings.SettingsSectionScreen

/** The Pantry tab's stack (#51): the pantry, and Settings' Pantry page from its menu (#412). */
fun NavGraphBuilder.pantryDestinations(navController: NavHostController) {
    composable(Routes.PANTRY) {
        PantryScreen(
            sendFileViewModel = hiltViewModel(),
            useItUpViewModel = hiltViewModel(),
            onOpenSettings = if (LocalFlagValues.current.pantrySettingsLink) {
                { navController.navigate(Routes.PANTRY_SETTINGS) }
            } else {
                null
            }
        )
    }

    // The same page as Settings > Pantry (#412), on this tab's own stack, so Back returns to the pantry.
    composable(Routes.PANTRY_SETTINGS) {
        SettingsSectionScreen(SettingsSection.PANTRY, onBack = { navController.popBackStack() })
    }
}
