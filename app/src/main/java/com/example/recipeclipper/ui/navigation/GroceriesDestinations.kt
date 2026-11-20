package com.example.recipeclipper.ui.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.example.recipeclipper.ui.groceries.GroceriesScreen

/**
 * The Groceries tab's stack (#50): the list, and a recipe opened from a line's tag (#422). A list
 * shared into the app (#149) opens here, in the "Add this list" sheet; lines added to the pantry
 * open the Pantry tab.
 */
fun NavGraphBuilder.groceriesDestinations(navController: NavHostController) {
    composable(Routes.GROCERIES) {
        GroceriesScreen(
            receiveViewModel = hiltViewModel(),
            sendFileViewModel = hiltViewModel(),
            onOpenPantry = { navController.selectTab(Tab.PANTRY) },
            onOpenRecipe = { id, servings -> navController.navigate(Routes.groceriesRecipe(id, servings)) }
        )
    }

    // A recipe tag's tap (#422): the recipe on this tab's own stack, so Back returns to the list.
    tabRecipe(Routes.GROCERIES_RECIPE, navController) { Routes.groceriesRecipe(it, null) }
}
