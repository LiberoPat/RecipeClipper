package com.example.recipeclipper.ui.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.recipeclipper.ui.pantry.PantryScreen

/** The Pantry tab's stack (#51): the pantry alone. */
fun NavGraphBuilder.pantryDestinations() {
    composable(Routes.PANTRY) {
        PantryScreen(sendFileViewModel = hiltViewModel())
    }
}
