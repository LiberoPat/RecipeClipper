package com.example.recipeclipper.ui.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.recipeclipper.ui.mealtypes.MealTypesScreen
import com.example.recipeclipper.ui.askai.askAiViewModelIfOn
import com.example.recipeclipper.ui.print.printViewModelIfOn
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
        WhatINeedScreen(
            onBack = { navController.popBackStack() },
            onOpenRecipe = { id, servings -> navController.navigate(Routes.weekRecipe(id, servings)) }
        )
    }

    composable(Routes.MEAL_TYPES) {
        MealTypesScreen(onBack = { navController.popBackStack() })
    }

    tabRecipe(Routes.WEEK_RECIPE, navController) { Routes.weekRecipe(it, null) }
}

/**
 * A recipe on a tab's own stack (#49, #422), opened at the servings the route carries (a planned
 * meal's), so Back returns to that tab's screen rather than to Recipes. [route] is the tab's recipe
 * route, [versionRoute] the one a version or original opened from the recipe takes.
 */
internal fun NavGraphBuilder.tabRecipe(route: String, navController: NavHostController, versionRoute: (Long) -> String) {
    composable(
        route = route,
        arguments = listOf(
            navArgument(RecipeViewModel.RECIPE_ID_ARG) { type = NavType.LongType },
            navArgument(RecipeViewModel.SERVINGS_ARG) {
                type = NavType.IntType
                defaultValue = 0
            },
            navArgument(RecipeViewModel.START_COOK_ARG) {
                type = NavType.BoolType
                defaultValue = false
            }
        )
    ) {
        RecipeScreen(
            onBack = { navController.popBackStack() },
            onEdit = { navController.navigate(Routes.edit(it)) },
            onOpenRecipe = { navController.navigate(versionRoute(it)) },
            planViewModel = hiltViewModel(),
            groceriesViewModel = hiltViewModel(),
            photosViewModel = hiltViewModel(),
            sendFileViewModel = hiltViewModel(),
            printViewModel = printViewModelIfOn(),
            useUpViewModel = hiltViewModel(),
            askAiViewModel = askAiViewModelIfOn()
        )
    }
}
