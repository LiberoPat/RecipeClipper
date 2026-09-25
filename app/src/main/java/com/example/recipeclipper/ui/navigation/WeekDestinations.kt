package com.example.recipeclipper.ui.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.recipeclipper.ui.mealtypes.MealTypesScreen
import com.example.recipeclipper.ui.recipe.RecipeScreen
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import com.example.recipeclipper.ui.week.WeekScreen
import com.example.recipeclipper.ui.week.WhatINeedScreen
import com.example.recipeclipper.ui.week.WhatINeedViewModel

/**
 * The Week tab's stack (#49), starting at [Routes.WEEK]. A planned recipe opens here, on the
 * Week's own stack, so Back returns to the week rather than to Recipes.
 */
fun NavGraphBuilder.weekDestinations(navController: NavHostController) {
    composable(Routes.WEEK) {
        WeekScreen(
            onOpenRecipe = { id, servings -> navController.navigate(Routes.weekRecipe(id, servings)) },
            onOpenMealTypes = { navController.navigate(Routes.MEAL_TYPES) },
            onOpenWhatINeed = { navController.navigate(Routes.whatINeed(it)) },
            groceriesViewModel = hiltViewModel()
        )
    }

    composable(
        route = Routes.WHAT_I_NEED,
        arguments = listOf(navArgument(WhatINeedViewModel.WEEK_START_ARG) { type = NavType.LongType })
    ) {
        WhatINeedScreen(onBack = { navController.popBackStack() })
    }

    composable(Routes.MEAL_TYPES) {
        MealTypesScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = Routes.WEEK_RECIPE,
        arguments = listOf(
            navArgument(RecipeViewModel.RECIPE_ID_ARG) { type = NavType.LongType },
            navArgument(RecipeViewModel.SERVINGS_ARG) {
                type = NavType.IntType
                defaultValue = 0
            }
        )
    ) {
        RecipeScreen(
            onBack = { navController.popBackStack() },
            onEdit = { navController.navigate(Routes.edit(it)) }
        )
    }
}
