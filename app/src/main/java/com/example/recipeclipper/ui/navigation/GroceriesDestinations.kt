package com.example.recipeclipper.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.recipeclipper.ui.groceries.GroceriesScreen

/** The Groceries tab's stack (#50): the list alone, for now. */
fun NavGraphBuilder.groceriesDestinations() {
    composable(Routes.GROCERIES) {
        GroceriesScreen()
    }
}
