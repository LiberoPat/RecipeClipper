package com.example.recipeclipper.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.recipeclipper.ui.mealtypes.MealTypesScreen
import com.example.recipeclipper.ui.recipe.RecipeScreen
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import com.example.recipeclipper.ui.week.WeekScreen

/**
 * The Week tab's stack (#49), starting at [Routes.WEEK]. A planned recipe opens here, on the
 * Week's own stack, so Back returns to the week rather than to Recipes.
 */
fun NavGraphBuilder.weekDestinations(navController: NavHostController) {
    composable(Routes.WEEK) {
        WeekScreen(
            onOpenRecipe = { id, servings -> navController.navigate(Routes.weekRecipe(id, servings)) },
            onOpenMealTypes = { navController.navigate(Routes.MEAL_TYPES) }
        )
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
