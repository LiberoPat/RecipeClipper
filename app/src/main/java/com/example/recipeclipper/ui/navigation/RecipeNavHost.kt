package com.example.recipeclipper.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.recipeclipper.ui.history.HistoryScreen
import com.example.recipeclipper.ui.home.HomeScreen
import com.example.recipeclipper.ui.listdetail.ListDetailScreen
import com.example.recipeclipper.ui.listdetail.ListDetailViewModel
import com.example.recipeclipper.ui.lists.ListsScreen
import com.example.recipeclipper.ui.recipe.RecipeScreen
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import com.example.recipeclipper.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val LISTS = "lists"
    const val LIST_DETAIL = "lists/{${ListDetailViewModel.LIST_ID_ARG}}"
    const val RECIPE = "recipe/{${RecipeViewModel.RECIPE_ID_ARG}}"

    // The share-target entry: parse, then persist.
    const val IMPORT = "recipe/import?${RecipeViewModel.URL_ARG}={${RecipeViewModel.URL_ARG}}"

    // The placeholder tabs (#47). Only reachable while BuildConfig.MEAL_PLAN_TABS is on.
    const val WEEK = "week"
    const val GROCERIES = "groceries"
    const val PANTRY = "pantry"

    fun recipe(id: Long) = "recipe/$id"
    fun list(id: Long) = "lists/$id"
    fun import(url: String) = "recipe/import?${RecipeViewModel.URL_ARG}=${Uri.encode(url)}"
}

/**
 * The single-stack app, as it is while `BuildConfig.MEAL_PLAN_TABS` is off: exactly the
 * Recipes graph, with no tab bar. With the flag on, [AppShell] nests the same destinations
 * under the Recipes tab instead.
 */
@Composable
fun RecipeNavHost(
    navController: NavHostController,
    recipes: NavGraphBuilder.(NavHostController) -> Unit = { recipesDestinations(it) }
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        recipes(navController)
    }
}

/**
 * Every destination of the Recipes stack, starting at [Routes.HOME]. Shared by the flag-off
 * [RecipeNavHost] and the Recipes tab of [AppShell], so both are the same graph.
 */
fun NavGraphBuilder.recipesDestinations(navController: NavHostController) {
    composable(Routes.HOME) {
        HomeScreen(
            onOpenUrl = { navController.navigate(Routes.import(it)) },
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) },
            onOpenHistory = { navController.navigate(Routes.HISTORY) },
            onOpenLists = { navController.navigate(Routes.LISTS) },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) }
        )
    }

    composable(Routes.LISTS) {
        ListsScreen(
            onBack = { navController.popBackStack() },
            onOpenList = { navController.navigate(Routes.list(it)) }
        )
    }

    composable(
        route = Routes.LIST_DETAIL,
        arguments = listOf(navArgument(ListDetailViewModel.LIST_ID_ARG) { type = NavType.LongType })
    ) {
        ListDetailScreen(
            onBack = { navController.popBackStack() },
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) }
        )
    }

    composable(Routes.HISTORY) {
        HistoryScreen(
            onBack = { navController.popBackStack() },
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) }
        )
    }

    // Opened from the gear beside the Home title. Any screen could navigate here: open
    // ViewModels collect AppPreferences.settings, so none is left showing stale units.
    composable(Routes.SETTINGS) {
        SettingsScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = Routes.RECIPE,
        arguments = listOf(navArgument(RecipeViewModel.RECIPE_ID_ARG) { type = NavType.LongType })
    ) {
        RecipeScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = Routes.IMPORT,
        arguments = listOf(
            navArgument(RecipeViewModel.URL_ARG) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) {
        RecipeScreen(onBack = { navController.popBackStack() })
    }
}
