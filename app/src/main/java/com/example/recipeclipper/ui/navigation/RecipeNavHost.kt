package com.example.recipeclipper.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.recipeclipper.data.PhotoPost
import com.example.recipeclipper.ui.clip.ClipScreen
import com.example.recipeclipper.ui.clip.ClipViewModel
import com.example.recipeclipper.ui.edit.EditRecipeScreen
import com.example.recipeclipper.ui.edit.EditRecipeViewModel
import com.example.recipeclipper.ui.recipes.RecipesScreen
import com.example.recipeclipper.ui.home.HomeScreen
import com.example.recipeclipper.ui.listdetail.ListDetailScreen
import com.example.recipeclipper.ui.listdetail.ListDetailViewModel
import com.example.recipeclipper.ui.lists.ListsScreen
import com.example.recipeclipper.ui.recipe.RecipeScreen
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import com.example.recipeclipper.ui.week.WhatINeedViewModel
import com.example.recipeclipper.ui.settings.DeveloperSettingsScreen
import com.example.recipeclipper.ui.settings.SettingsScreen
import com.example.recipeclipper.ui.tour.LocalTooltips

object Routes {
    const val HOME = "home"
    // The library of every recipe (#102); it replaced History, whose route was `history`.
    const val RECIPES = "recipes"
    const val SETTINGS = "settings"

    // Hidden: seven taps on the version in Settings (#87).
    const val DEVELOPER_SETTINGS = "settings/developer"
    const val LISTS = "lists"
    const val LIST_DETAIL = "lists/{${ListDetailViewModel.LIST_ID_ARG}}"
    const val RECIPE =
        "recipe/{${RecipeViewModel.RECIPE_ID_ARG}}?${RecipeViewModel.COOK_ARG}={${RecipeViewModel.COOK_ARG}}"

    // The share-target entry: parse, then persist.
    const val IMPORT = "recipe/import?${RecipeViewModel.URL_ARG}={${RecipeViewModel.URL_ARG}}"

    // The tabs (#47). Only reachable while the mealPlan flag (#87) is on.
    const val WEEK = "week"

    // The Week tab's own stack (#49): a planned recipe opens at its planned servings.
    const val WEEK_RECIPE =
        "week/recipe/{${RecipeViewModel.RECIPE_ID_ARG}}?${RecipeViewModel.SERVINGS_ARG}={${RecipeViewModel.SERVINGS_ARG}}"
    const val MEAL_TYPES = "week/meal-types"
    const val WHAT_I_NEED = "week/need/{${WhatINeedViewModel.WEEK_START_ARG}}"
    const val GROCERIES = "groceries"
    const val PANTRY = "pantry"

    // Edit a recipe (#29), or with no id type a new one in.
    const val EDIT = "edit?${EditRecipeViewModel.RECIPE_ID_ARG}={${EditRecipeViewModel.RECIPE_ID_ARG}}"

    fun recipe(id: Long) = "recipe/$id"
    fun weekRecipe(id: Long, servings: Int?) =
        "week/recipe/$id" + (servings?.let { "?${RecipeViewModel.SERVINGS_ARG}=$it" } ?: "")
    fun whatINeed(weekStart: Long) = "week/need/$weekStart"
    fun edit(id: Long) ="edit?${EditRecipeViewModel.RECIPE_ID_ARG}=$id"
    const val NEW_RECIPE = "edit"

    // From a timer notification: the recipe, opened in cook mode.
    fun cookRecipe(id: Long) = "recipe/$id?${RecipeViewModel.COOK_ARG}=true"

    // "Clip it yourself" (#37), from a page with no recipe data, or opened by itself in the
    // import's place when Reddit blocks the app's read of a post (#213: `blocked`, with a note).
    // `check` (#220): opened in the import's place for the cook to pass Cloudflare's check.
    const val CLIP = "clip?${ClipViewModel.URL_ARG}={${ClipViewModel.URL_ARG}}" +
        "&${ClipViewModel.BLOCKED_ARG}={${ClipViewModel.BLOCKED_ARG}}" +
        "&${ClipViewModel.CHECK_ARG}={${ClipViewModel.CHECK_ARG}}"

    fun clip(url: String, blocked: Boolean = false, check: Boolean = false) =
        "clip?${ClipViewModel.URL_ARG}=${Uri.encode(url)}&${ClipViewModel.BLOCKED_ARG}=$blocked" +
            "&${ClipViewModel.CHECK_ARG}=$check"

    // "Read the photo" (#198): the editor, filled from a Reddit post's photos read on the device.
    const val EDIT_PHOTO = "edit/photo?${EditRecipeViewModel.PHOTO_URL_ARG}={${EditRecipeViewModel.PHOTO_URL_ARG}}" +
        "&${EditRecipeViewModel.PHOTO_TITLE_ARG}={${EditRecipeViewModel.PHOTO_TITLE_ARG}}" +
        "&${EditRecipeViewModel.PHOTO_IMAGES_ARG}={${EditRecipeViewModel.PHOTO_IMAGES_ARG}}"

    fun editPhoto(post: PhotoPost) = "edit/photo?${EditRecipeViewModel.PHOTO_URL_ARG}=${Uri.encode(post.url)}" +
        "&${EditRecipeViewModel.PHOTO_TITLE_ARG}=${Uri.encode(post.title)}" +
        "&${EditRecipeViewModel.PHOTO_IMAGES_ARG}=${Uri.encode(post.imageUrls.joinToString("\n"))}"
    // "Scan a recipe" (#226): the same editor, filled from the cook's own pages (local URIs).
    const val SCAN = "edit/scan?${EditRecipeViewModel.SCAN_PAGES_ARG}={${EditRecipeViewModel.SCAN_PAGES_ARG}}"

    fun scan(pages: List<String>) =
        "edit/scan?${EditRecipeViewModel.SCAN_PAGES_ARG}=${Uri.encode(pages.joinToString("\n"))}"
    fun list(id: Long) = "lists/$id"
    fun import(url: String) = "recipe/import?${RecipeViewModel.URL_ARG}=${Uri.encode(url)}"
}

/**
 * The single-stack app, as it is while the `mealPlan` flag is off: exactly the
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
            onOpenRecipes = { navController.navigate(Routes.RECIPES) },
            onOpenLists = { navController.navigate(Routes.LISTS) },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            onNewRecipe = { navController.navigate(Routes.NEW_RECIPE) },
            onScan = { navController.navigate(Routes.scan(it)) }
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

    composable(Routes.RECIPES) {
        // The + menu: "Type a recipe" is Home's "+ New recipe"; "Paste a link" is Home's field.
        RecipesScreen(
            onBack = { navController.popBackStack() },
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) },
            onNewRecipe = { navController.navigate(Routes.NEW_RECIPE) },
            onOpenUrl = { navController.navigate(Routes.import(it)) },
            onScan = { navController.navigate(Routes.scan(it)) }
        )
    }

    // Opened from the gear beside the Home title. Any screen could navigate here: open
    // ViewModels collect AppPreferences.settings, so none is left showing stale units.
    composable(Routes.SETTINGS) {
        // "Show tips again" (#190): every tooltip once more, from the app's one TooltipsViewModel.
        val tooltips = LocalTooltips.current
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenDeveloperSettings = { navController.navigate(Routes.DEVELOPER_SETTINGS) },
            onShowTips = { tooltips?.onReplay() }
        )
    }

    composable(Routes.DEVELOPER_SETTINGS) {
        DeveloperSettingsScreen(onBack = { navController.popBackStack() })
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
            onEdit = { navController.navigate(Routes.edit(it)) },
            sendFileViewModel = hiltViewModel()
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
            onClip = { navController.navigate(Routes.clip(it)) },
            // Replaces the import, so Back from the clip never lands on an error screen (#213).
            onClipBlocked = {
                navController.navigate(Routes.clip(it, blocked = true)) {
                    popUpTo(Routes.IMPORT) { inclusive = true }
                }
            },
            // Cloudflare's check (#220), likewise in the import's place.
            onHumanCheck = {
                navController.navigate(Routes.clip(it, check = true)) {
                    popUpTo(Routes.IMPORT) { inclusive = true }
                }
            },
            onReadPhoto = { navController.navigate(Routes.editPhoto(it)) },
            sendFileViewModel = hiltViewModel()
        )
    }

    composable(
        route = Routes.EDIT_PHOTO,
        arguments = listOf(
            navArgument(EditRecipeViewModel.PHOTO_URL_ARG) { type = NavType.StringType },
            navArgument(EditRecipeViewModel.PHOTO_TITLE_ARG) { type = NavType.StringType; defaultValue = "" },
            navArgument(EditRecipeViewModel.PHOTO_IMAGES_ARG) { type = NavType.StringType; defaultValue = "" }
        )
    ) {
        EditRecipeScreen(
            onBack = { navController.popBackStack() },
            // The checked recipe replaces both the editor and the post's error screen, as a
            // saved clip does, so Back from it goes where the share came from.
            onSaved = { id ->
                navController.navigate(Routes.recipe(id)) {
                    popUpTo(Routes.IMPORT) { inclusive = true }
                }
            }
        )
    }

    composable(
        route = Routes.SCAN,
        arguments = listOf(
            navArgument(EditRecipeViewModel.SCAN_PAGES_ARG) { type = NavType.StringType; defaultValue = "" }
        )
    ) {
        EditRecipeScreen(
            onBack = { navController.popBackStack() },
            // The saved recipe replaces the review, as a typed-in one does.
            onSaved = { id ->
                navController.popBackStack()
                navController.navigate(Routes.recipe(id))
            }
        )
    }

    composable(
        route = Routes.CLIP,
        arguments = listOf(
            navArgument(ClipViewModel.URL_ARG) { type = NavType.StringType },
            navArgument(ClipViewModel.BLOCKED_ARG) { type = NavType.BoolType; defaultValue = false },
            navArgument(ClipViewModel.CHECK_ARG) { type = NavType.BoolType; defaultValue = false }
        )
    ) { entry ->
        // Reddit's block (#213) or Cloudflare's check (#220) opened it in the import's place: no
        // error screen under it.
        val blocked = entry.arguments?.getBoolean(ClipViewModel.BLOCKED_ARG) == true ||
            entry.arguments?.getBoolean(ClipViewModel.CHECK_ARG) == true
        ClipScreen(
            onCancel = { navController.popBackStack() },
            // The saved clip replaces the clip screen and the error screen under it, so Back
            // from the recipe goes where the share came from.
            onSaved = { id ->
                navController.navigate(Routes.recipe(id)) {
                    popUpTo(if (blocked) Routes.CLIP else Routes.IMPORT) { inclusive = true }
                }
            }
        )
    }
}
