package com.example.recipeclipper.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.recipeclipper.ui.clip.ClipScreen
import com.example.recipeclipper.ui.clip.ClipViewModel
import com.example.recipeclipper.ui.edit.EditRecipeScreen
import com.example.recipeclipper.ui.edit.EditRecipeViewModel
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
    const val RECIPE =
        "recipe/{${RecipeViewModel.RECIPE_ID_ARG}}?${RecipeViewModel.COOK_ARG}={${RecipeViewModel.COOK_ARG}}"

    // The share-target entry: parse, then persist.
    const val IMPORT = "recipe/import?${RecipeViewModel.URL_ARG}={${RecipeViewModel.URL_ARG}}"

    // The placeholder tabs (#47). Only reachable while BuildConfig.MEAL_PLAN_TABS is on.
    const val WEEK = "week"
    const val GROCERIES = "groceries"
    const val PANTRY = "pantry"

    // Edit a recipe (#29), or with no id type a new one in.
    const val EDIT = "edit?${EditRecipeViewModel.RECIPE_ID_ARG}={${EditRecipeViewModel.RECIPE_ID_ARG}}"

    fun recipe(id: Long) = "recipe/$id"
    fun edit(id: Long) = "edit?${EditRecipeViewModel.RECIPE_ID_ARG}=$id"
    const val NEW_RECIPE = "edit"

    // From a timer notification: the recipe, opened in cook mode.
    fun cookRecipe(id: Long) = "recipe/$id?${RecipeViewModel.COOK_ARG}=true"

    // "Clip it yourself" (#37), from a page with no recipe data.
    const val CLIP = "clip?${ClipViewModel.URL_ARG}={${ClipViewModel.URL_ARG}}"

    fun clip(url: String) = "clip?${ClipViewModel.URL_ARG}=${Uri.encode(url)}"
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
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            onNewRecipe = { navController.navigate(Routes.NEW_RECIPE) }
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
        arguments = listOf(
            navArgument(RecipeViewModel.RECIPE_ID_ARG) { type = NavType.LongType },
            navArgument(RecipeViewModel.COOK_ARG) {
                type = NavType.BoolType
                defaultValue = false
            }
        )
    ) {
        RecipeScreen(
            onBack = { navController.popBackStack() },
            onEdit = { navController.navigate(Routes.edit(it)) }
        )
    }

    // Saving replaces the edit screen and, when editing, the recipe screen under it, so
    // the recipe opens afresh with its new content instead of the copy it loaded before.
    composable(
        route = Routes.EDIT,
        arguments = listOf(
            navArgument(EditRecipeViewModel.RECIPE_ID_ARG) {
                type = NavType.LongType
                defaultValue = 0L
            }
        )
    ) { entry ->
        val editing = (entry.arguments?.getLong(EditRecipeViewModel.RECIPE_ID_ARG) ?: 0L) > 0
        EditRecipeScreen(
            onBack = { navController.popBackStack() },
            onSaved = { id ->
                navController.popBackStack()
                if (editing) navController.popBackStack()
                navController.navigate(Routes.recipe(id))
            }
        )
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
        RecipeScreen(
            onBack = { navController.popBackStack() },
            onEdit = { navController.navigate(Routes.edit(it)) },
            onClip = { navController.navigate(Routes.clip(it)) }
        )
    }

    composable(
        route = Routes.CLIP,
        arguments = listOf(navArgument(ClipViewModel.URL_ARG) { type = NavType.StringType })
    ) {
        ClipScreen(
            onCancel = { navController.popBackStack() },
            // The saved clip replaces both the error screen and the clip screen, so Back
            // from the recipe goes where the share came from.
            onSaved = { id ->
                navController.navigate(Routes.recipe(id)) {
                    popUpTo(Routes.IMPORT) { inclusive = true }
                }
            }
        )
    }
}
