package com.example.recipeclipper.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.recipeclipper.data.PhotoPost
import com.example.recipeclipper.ui.clip.ClipScreen
import com.example.recipeclipper.ui.clip.ClipViewModel
import com.example.recipeclipper.ui.edit.EditRecipeScreen
import com.example.recipeclipper.ui.edit.EditRecipeViewModel
import com.example.recipeclipper.ui.article.ArticleChoiceScreen
import com.example.recipeclipper.ui.article.ArticleChoiceViewModel
import com.example.recipeclipper.ui.mealplan.CutOffScreen
import com.example.recipeclipper.ui.mealplan.MealPlanReviewScreen
import com.example.recipeclipper.ui.mealplan.MealPlanReviewViewModel
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.ui.recipes.LibraryRoot
import com.example.recipeclipper.ui.recipes.RecipesScreen
import com.example.recipeclipper.ui.recipes.RecipesViewModel
import com.example.recipeclipper.ui.home.HomeScreen
import com.example.recipeclipper.ui.listdetail.ListDetailScreen
import com.example.recipeclipper.ui.listdetail.ListDetailViewModel
import com.example.recipeclipper.ui.lists.ListsScreen
import com.example.recipeclipper.ui.askai.askAiViewModelIfOn
import com.example.recipeclipper.ui.print.printViewModelIfOn
import com.example.recipeclipper.ui.recipe.RecipeScreen
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import com.example.recipeclipper.ui.week.WhatINeedViewModel
import com.example.recipeclipper.ui.pro.ProScreen
import com.example.recipeclipper.ui.settings.DeveloperSettingsScreen
import com.example.recipeclipper.ui.settings.SettingsScreen
import com.example.recipeclipper.ui.settings.SettingsSection
import com.example.recipeclipper.ui.settings.SettingsSectionScreen
import com.example.recipeclipper.ui.tour.LocalTooltips
import com.example.recipeclipper.data.flags.libraryFirst
import com.example.recipeclipper.ui.common.LocalFlagValues

object Routes {
    const val HOME = "home"
    // The library of every recipe (#102); it replaced History, whose route was `history`.
    const val RECIPES = "recipes"

    // An automatic view of the cookbook (#345): `NOT_IN_A_LIST`, `COOKED`, `TYPED` or `CHAT`.
    const val RECIPES_VIEW = "recipes/view/{${RecipesViewModel.VIEW_ARG}}"
    fun recipesView(view: RecipeFilter) = "recipes/view/${view.name}"
    const val SETTINGS = "settings"

    // Hidden: seven taps on the version in Settings (#87).
    const val DEVELOPER_SETTINGS = "settings/developer"

    // Settings' pages (#286), one per section of its index: `settings/units`, `settings/help`…
    fun settingsSection(section: SettingsSection) = "settings/${section.route}"

    // The Pro screen (#413) on the Recipes stack: Settings' Pro page, and where every locked extra here leads.
    val PRO = settingsSection(SettingsSection.PRO)

    // The same screen on the Week's own stack, so Back returns to the week.
    const val WEEK_PRO = "week/pro"
    const val LISTS = "lists"
    const val LIST_DETAIL = "lists/{${ListDetailViewModel.LIST_ID_ARG}}"
    // `servings` and `startCook` (#375): the library's Today card starts a planned meal on the Recipes
    // stack, as the Week's own route does on the Week's.
    const val RECIPE =
        "recipe/{${RecipeViewModel.RECIPE_ID_ARG}}?${RecipeViewModel.COOK_ARG}={${RecipeViewModel.COOK_ARG}}" +
            "&${RecipeViewModel.SERVINGS_ARG}={${RecipeViewModel.SERVINGS_ARG}}" +
            "&${RecipeViewModel.START_COOK_ARG}={${RecipeViewModel.START_COOK_ARG}}"

    // The share-target entry: parse, then persist.
    const val IMPORT = "recipe/import?${RecipeViewModel.URL_ARG}={${RecipeViewModel.URL_ARG}}"

    // The tabs (#47).
    const val WEEK = "week"

    // The Week tab's own stack (#49): a planned recipe opens at its planned servings.
    const val WEEK_RECIPE =
        "week/recipe/{${RecipeViewModel.RECIPE_ID_ARG}}?${RecipeViewModel.SERVINGS_ARG}={${RecipeViewModel.SERVINGS_ARG}}" +
            "&${RecipeViewModel.START_COOK_ARG}={${RecipeViewModel.START_COOK_ARG}}"
    const val MEAL_TYPES = "week/meal-types"
    const val WHAT_I_NEED = "week/need/{${WhatINeedViewModel.WEEK_START_ARG}}"
    const val GROCERIES = "groceries"

    // The Groceries tab's own stack (#422): a line's recipe tag opens it here, at its planned servings.
    const val GROCERIES_RECIPE =
        "groceries/recipe/{${RecipeViewModel.RECIPE_ID_ARG}}?${RecipeViewModel.SERVINGS_ARG}={${RecipeViewModel.SERVINGS_ARG}}"
    const val PANTRY = "pantry"

    // Edit a recipe (#29), or with no id type a new one in.
    const val EDIT = "edit?${EditRecipeViewModel.RECIPE_ID_ARG}={${EditRecipeViewModel.RECIPE_ID_ARG}}"

    fun recipe(id: Long) = "recipe/$id"
    // `startCook` (#402): Home's Today card opens the planned recipe in cook mode.
    fun weekRecipe(id: Long, servings: Int?, startCook: Boolean = false) =
        "week/recipe/$id" + listOfNotNull(
            servings?.let { "${RecipeViewModel.SERVINGS_ARG}=$it" },
            "${RecipeViewModel.START_COOK_ARG}=true".takeIf { startCook }
        ).let { if (it.isEmpty()) "" else "?" + it.joinToString("&") }
    fun groceriesRecipe(id: Long, servings: Int?) =
        "groceries/recipe/$id" + (servings?.let { "?${RecipeViewModel.SERVINGS_ARG}=$it" } ?: "")
    fun whatINeed(weekStart: Long) = "week/need/$weekStart"
    fun edit(id: Long) ="edit?${EditRecipeViewModel.RECIPE_ID_ARG}=$id"
    const val NEW_RECIPE = "edit"

    // From a timer notification or Home's "Resume cooking" (#286): the recipe, in cook mode.
    fun cookRecipe(id: Long) = "recipe/$id?${RecipeViewModel.COOK_ARG}=true"

    // The library's Today card (#375): a planned meal at its planned servings, on the Recipes stack so Back
    // returns to the library, and with `startCook` in cook mode (`weekRecipe` is the Week's own).
    fun plannedRecipe(id: Long, servings: Int?, startCook: Boolean = false) = "recipe/$id" + listOfNotNull(
        servings?.let { "${RecipeViewModel.SERVINGS_ARG}=$it" },
        "${RecipeViewModel.START_COOK_ARG}=true".takeIf { startCook }
    ).let { if (it.isEmpty()) "" else "?" + it.joinToString("&") }

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
    // A recipe written inside an article (#435): the same editor, filled from the page's list of
    // amounts and its method, found by the page's link and the recipe's place on the page (the
    // repository remembers what it read).
    const val EDIT_ARTICLE = "edit/article?${EditRecipeViewModel.ARTICLE_URL_ARG}={${EditRecipeViewModel.ARTICLE_URL_ARG}}" +
        "&${EditRecipeViewModel.ARTICLE_INDEX_ARG}={${EditRecipeViewModel.ARTICLE_INDEX_ARG}}"

    fun editArticle(url: String, index: Int) =
        "edit/article?${EditRecipeViewModel.ARTICLE_URL_ARG}=${Uri.encode(url)}&${EditRecipeViewModel.ARTICLE_INDEX_ARG}=$index"

    // Several recipes written inside one article (#435): the chooser, found by the page's link.
    const val ARTICLE_CHOICE = "article/choose?${ArticleChoiceViewModel.URL_ARG}={${ArticleChoiceViewModel.URL_ARG}}"

    fun articleChoice(url: String) = "article/choose?${ArticleChoiceViewModel.URL_ARG}=${Uri.encode(url)}"

    // "Scan a recipe" (#226): the same editor, filled from the cook's own pages (local URIs).
    const val SCAN = "edit/scan?${EditRecipeViewModel.SCAN_PAGES_ARG}={${EditRecipeViewModel.SCAN_PAGES_ARG}}"

    fun scan(pages: List<String>) =
        "edit/scan?${EditRecipeViewModel.SCAN_PAGES_ARG}=${Uri.encode(pages.joinToString("\n"))}"

    // Text shared in (#306): the same editor, filled from the text, split or as written. The
    // route carries only the inbox key (`SharedTextInbox`), never the text (#334).
    const val EDIT_TEXT = "edit/text?${EditRecipeViewModel.SHARED_TEXT_ARG}={${EditRecipeViewModel.SHARED_TEXT_ARG}}"

    fun editText(key: String) = "edit/text?${EditRecipeViewModel.SHARED_TEXT_ARG}=${Uri.encode(key)}"

    // A week of recipes shared in (#352): the review, found by the inbox key the route carries.
    const val MEAL_PLAN = "mealplan?${MealPlanReviewViewModel.PLAN_ARG}={${MealPlanReviewViewModel.PLAN_ARG}}"

    fun mealPlan(key: String) = "mealplan?${MealPlanReviewViewModel.PLAN_ARG}=${Uri.encode(key)}"

    // An AI's answer shared in that was cut off before its recipe ended (#352): says so, and offers a message to ask for it again.
    const val CUT_OFF = "cutoff"
    fun list(id: Long) = "lists/$id"
    fun import(url: String) = "recipe/import?${RecipeViewModel.URL_ARG}=${Uri.encode(url)}"
}

/**
 * Text shared in (#306): the editor's review, found by the inbox key the route carries (#334).
 * [viewModel] is the screen's, Hilt's by default; a test passes its own over the entry's arguments.
 */
fun NavGraphBuilder.editTextDestination(
    navController: NavHostController,
    viewModel: @Composable (NavBackStackEntry) -> EditRecipeViewModel = { hiltViewModel() }
) {
    composable(
        route = Routes.EDIT_TEXT,
        arguments = listOf(
            navArgument(EditRecipeViewModel.SHARED_TEXT_ARG) { type = NavType.StringType; defaultValue = "" }
        )
    ) { entry ->
        EditRecipeScreen(
            onBack = { navController.popBackStack() },
            // As a scan's (#226): the saved recipe replaces the review.
            onSaved = { id ->
                navController.popBackStack()
                navController.navigate(Routes.recipe(id))
            },
            viewModel = viewModel(entry)
        )
    }
}

/**
 * A week of recipes shared in (#352): the meal-plan review. Saving replaces it with Week when any
 * recipe was planned, else with where it came from.
 */
fun NavGraphBuilder.mealPlanDestination(
    navController: NavHostController,
    viewModel: @Composable (NavBackStackEntry) -> MealPlanReviewViewModel = { hiltViewModel() }
) {
    composable(
        route = Routes.MEAL_PLAN,
        arguments = listOf(
            navArgument(MealPlanReviewViewModel.PLAN_ARG) { type = NavType.StringType; defaultValue = "" }
        )
    ) { entry ->
        MealPlanReviewScreen(
            onBack = { navController.popBackStack() },
            onFinished = { planned ->
                navController.popBackStack()
                if (planned) navController.selectTab(Tab.WEEK)
            },
            viewModel = viewModel(entry),
            onOpenPro = { navController.navigate(Routes.PRO) }
        )
    }
}

/** A shared AI answer cut off before its recipe ended (#352): the cause on screen, Back to where it came from. */
fun NavGraphBuilder.cutOffDestination(navController: NavHostController) {
    composable(Routes.CUT_OFF) {
        CutOffScreen(onBack = { navController.popBackStack() })
    }
}

/** Every destination of the Recipes stack, starting at [Routes.HOME]: the Recipes tab of [AppShell]. */
fun NavGraphBuilder.recipesDestinations(navController: NavHostController) {
    composable(Routes.HOME) {
        HomeScreen(
            onOpenUrl = { navController.navigate(Routes.import(it)) },
            onOpenPasted = navController::openPasted,
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) },
            onResumeCooking = { navController.navigate(Routes.cookRecipe(it)) },
            // Today's meal (#402): on the Week's stack, as the Week opens a planned recipe.
            onOpenPlanned = { id, servings, cook ->
                navController.selectTab(Tab.WEEK)
                navController.navigate(Routes.weekRecipe(id, servings, cook))
            },
            onOpenRecipes = { navController.navigate(Routes.RECIPES) },
            onOpenLists = { navController.navigate(Routes.LISTS) },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            onNewRecipe = { navController.navigate(Routes.NEW_RECIPE) },
            onScan = { navController.navigate(Routes.scan(it)) },
            onOpenPro = { navController.navigate(Routes.PRO) }
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
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) },
            printViewModel = printViewModelIfOn()
        )
    }

    composable(Routes.RECIPES) {
        // With libraryFirst (#375) this is the tab's first screen: Home's jobs (the card, the ways in, the
        // gear) are here, and the + opens the Add a recipe sheet. Without, it is pushed from Home.
        val libraryFirst = LocalFlagValues.current.libraryFirst
        RecipesScreen(
            onBack = { navController.popBackStack() },
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) },
            onNewRecipe = { navController.navigate(Routes.NEW_RECIPE) },
            onOpenUrl = { navController.navigate(Routes.import(it)) },
            onOpenPasted = navController::openPasted,
            onScan = { navController.navigate(Routes.scan(it)) },
            onOpenView = { navController.navigate(Routes.recipesView(it)) },
            onOpenLists = { navController.navigate(Routes.LISTS) },
            libraryRoot = if (!libraryFirst) null else LibraryRoot(
                viewModel = hiltViewModel(),
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onResumeCooking = { navController.navigate(Routes.cookRecipe(it)) },
                // Today's meal: on this stack, so Back returns to the library (Home's went to the Week's).
                onOpenPlanned = { id, servings, cook -> navController.navigate(Routes.plannedRecipe(id, servings, cook)) },
                onOpenPro = { navController.navigate(Routes.PRO) }
            )
        )
    }

    // One cookbook (#345): Not in a list, Cooked or Typed by you, as a screen of its own.
    composable(
        route = Routes.RECIPES_VIEW,
        arguments = listOf(navArgument(RecipesViewModel.VIEW_ARG) { type = NavType.StringType })
    ) {
        RecipesScreen(
            onBack = { navController.popBackStack() },
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) }
        )
    }

    // Opened from the gear beside the Home title (or, with libraryFirst, the Recipes title, #375). Any screen could navigate here: open
    // ViewModels collect AppPreferences.settings, so none is left showing stale units.
    composable(Routes.SETTINGS) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenSection = { navController.navigate(Routes.settingsSection(it)) },
            onOpenDeveloperSettings = { navController.navigate(Routes.DEVELOPER_SETTINGS) }
        )
    }

    // Each section's page (#286); Back returns to the index.
    SettingsSection.entries.forEach { section ->
        composable(Routes.settingsSection(section)) {
            if (section == SettingsSection.PRO) {
                // Pro (#413): its page is the Pro screen itself, the one every locked extra opens.
                ProScreen(onBack = { navController.popBackStack() })
                return@composable
            }
            // "Show tips again" (#190): every tooltip once more, from the app's one TooltipsViewModel.
            val tooltips = LocalTooltips.current
            SettingsSectionScreen(
                section,
                onBack = { navController.popBackStack() },
                onShowTips = { tooltips?.onReplay() },
                onOpenPro = { navController.navigate(Routes.PRO) }
            )
        }
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
            },
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
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) },
            planViewModel = hiltViewModel(),
            groceriesViewModel = hiltViewModel(),
            photosViewModel = hiltViewModel(),
            sendFileViewModel = hiltViewModel(),
            printViewModel = printViewModelIfOn(),
            useUpViewModel = hiltViewModel(),
            askAiViewModel = askAiViewModelIfOn()
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
            onOpenRecipe = { navController.navigate(Routes.recipe(it)) },
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
            onArticle = { url, count -> navController.openArticle(url, count, Routes.IMPORT) },
            planViewModel = hiltViewModel(),
            groceriesViewModel = hiltViewModel(),
            photosViewModel = hiltViewModel(),
            sendFileViewModel = hiltViewModel(),
            printViewModel = printViewModelIfOn(),
            useUpViewModel = hiltViewModel(),
            askAiViewModel = askAiViewModelIfOn()
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
        route = Routes.EDIT_ARTICLE,
        arguments = listOf(
            navArgument(EditRecipeViewModel.ARTICLE_URL_ARG) { type = NavType.StringType; defaultValue = "" },
            navArgument(EditRecipeViewModel.ARTICLE_INDEX_ARG) { type = NavType.IntType; defaultValue = 0 }
        )
    ) {
        EditRecipeScreen(
            // The import is gone from under it (#435), so Back goes where the share came from, or
            // to the chooser it was opened from.
            onBack = { navController.popBackStack() },
            // The saved recipe replaces the editor: Back from it goes where the share came from,
            // or to the chooser, which then shows this one as saved.
            onSaved = { id ->
                navController.navigate(Routes.recipe(id)) {
                    popUpTo(Routes.EDIT_ARTICLE) { inclusive = true }
                }
            }
        )
    }

    composable(
        route = Routes.ARTICLE_CHOICE,
        arguments = listOf(navArgument(ArticleChoiceViewModel.URL_ARG) { type = NavType.StringType; defaultValue = "" })
    ) {
        ArticleChoiceScreen(
            onBack = { navController.popBackStack() },
            onCheck = { url, index -> navController.navigate(Routes.editArticle(url, index)) },
            onOpenSaved = { navController.navigate(Routes.recipe(it)) }
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

    editTextDestination(navController)
    mealPlanDestination(navController)
    cutOffDestination(navController)

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
            // Past Cloudflare's check, recipes written inside the article (#435), in the clip's place.
            onArticle = { url, count -> navController.openArticle(url, count, Routes.CLIP) },
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

/**
 * Recipes written inside a shared page's article (#435): the editor on the one, or the chooser
 * over several, in [replacing]'s place (the import, or the clip that passed Cloudflare's check),
 * so Back never lands on a screen that would open them again.
 */
internal fun NavHostController.openArticle(url: String, count: Int, replacing: String) {
    navigate(if (count == 1) Routes.editArticle(url, 0) else Routes.articleChoice(url)) {
        popUpTo(replacing) { inclusive = true }
    }
}
