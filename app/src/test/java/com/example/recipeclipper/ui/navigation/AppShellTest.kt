package com.example.recipeclipper.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.ui.listdetail.ListDetailViewModel
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tab shell (#47). The Recipes graph is a
 * stand-in with the real routes and one line of text per screen (the real screens need Hilt);
 * what's under test is the shell: which tab shows, where the bar shows, that each tab keeps its
 * own back stack, and that a shared link always lands in Recipes.
 */
@RunWith(AndroidJUnit4::class)
class AppShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun NavGraphBuilder.stubRecipes() {
        composable(Routes.HOME) { Text("stub:home") }
        composable(Routes.RECIPES) { Text("stub:recipes") }
        composable(Routes.SETTINGS) { Text("stub:settings") }
        composable(Routes.LISTS) { Text("stub:lists") }
        composable(
            Routes.LIST_DETAIL,
            arguments = listOf(navArgument(ListDetailViewModel.LIST_ID_ARG) { type = NavType.LongType })
        ) { Text("stub:list ${it.arguments?.getLong(ListDetailViewModel.LIST_ID_ARG)}") }
        composable(
            Routes.RECIPE,
            arguments = listOf(navArgument(RecipeViewModel.RECIPE_ID_ARG) { type = NavType.LongType })
        ) { Text("stub:recipe ${it.arguments?.getLong(RecipeViewModel.RECIPE_ID_ARG)}") }
        composable(
            Routes.IMPORT,
            arguments = listOf(navArgument(RecipeViewModel.URL_ARG) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { Text("stub:import ${it.arguments?.getString(RecipeViewModel.URL_ARG)}") }
    }

    /** The Week tab's real screens need Hilt too (#49). */
    private fun NavGraphBuilder.stubWeek() {
        composable(Routes.WEEK) { Text("stub:week") }
    }

    /** And the Groceries tab's (#50). */
    private fun NavGraphBuilder.stubGroceries() {
        composable(Routes.GROCERIES) { Text("stub:groceries") }
    }

    /** And the Pantry tab's (#51). */
    private fun NavGraphBuilder.stubPantry() {
        composable(Routes.PANTRY) { Text("stub:pantry") }
    }

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            AppShell(
                nav, recipes = { stubRecipes() }, week = { stubWeek() },
                groceries = { stubGroceries() }, pantry = { stubPantry() }
            )
        }
        compose.onNodeWithText("stub:home").assertIsDisplayed()
    }

    private fun onNav(action: NavHostController.() -> Unit) {
        compose.runOnIdle { nav.action() }
        compose.waitForIdle()
    }

    /** The bar item, not the placeholder title with the same word. */
    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())
    private fun bar() = compose.onNodeWithTag("tabBar")

    @Test
    fun theBarShowsFourTabsInOrderWithRecipesOpen() {
        show()

        bar().assertIsDisplayed()
        val lefts = listOf("Recipes", "Week", "Groceries", "Pantry").map {
            tab(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(lefts.sorted(), lefts)
        tab("Recipes").assertIsSelected()
        tab("Week").assertIsNotSelected()
    }

    @Test
    fun theBarShowsOnTheRecipesListScreensAndHidesOnARecipe() {
        show()

        for (route in listOf(Routes.RECIPES, Routes.LISTS, Routes.list(4), Routes.SETTINGS)) {
            onNav { navigate(route) }
            bar().assertIsDisplayed()
        }

        onNav { navigate(Routes.recipe(7)) }
        compose.onNodeWithText("stub:recipe 7").assertIsDisplayed()
        bar().assertDoesNotExist()

        onNav { popBackStack() }
        bar().assertIsDisplayed()

        onNav { navigate(Routes.import("https://example.com/soup")) }
        bar().assertDoesNotExist()
    }

    @Test
    fun theOtherTabsShowTheirScreens() {
        show()

        tab("Week").performClick()
        compose.onNodeWithText("stub:week").assertIsDisplayed()
        tab("Week").assertIsSelected()

        tab("Groceries").performClick()
        compose.onNodeWithText("stub:groceries").assertIsDisplayed()

        tab("Pantry").performClick()
        compose.onNodeWithText("stub:pantry").assertIsDisplayed()
        tab("Pantry").assertIsSelected()
        tab("Recipes").assertIsNotSelected()
    }

    @Test
    fun eachTabKeepsItsOwnBackStack() {
        show()
        onNav { navigate(Routes.LISTS) }
        onNav { navigate(Routes.list(4)) }

        tab("Week").performClick()
        compose.onNodeWithText("stub:week").assertIsDisplayed()

        tab("Recipes").performClick()
        compose.onNodeWithText("stub:list 4").assertIsDisplayed()
        onNav { popBackStack() }
        compose.onNodeWithText("stub:lists").assertIsDisplayed()
    }

    @Test
    fun choosingTheOpenTabAgainGoesBackToItsFirstScreen() {
        show()
        onNav { navigate(Routes.RECIPES) }

        tab("Recipes").performClick()

        compose.onNodeWithText("stub:home").assertIsDisplayed()
        tab("Recipes").assertIsSelected()
    }

    @Test
    fun aShareFromAnotherTabLandsInRecipesOnTopOfItsStack() {
        show()
        onNav { navigate(Routes.RECIPES) }
        tab("Pantry").performClick()
        compose.onNodeWithText("stub:pantry").assertIsDisplayed()

        onNav { openRoute(Routes.import("https://example.com/soup")) }

        compose.onNodeWithText("stub:import https://example.com/soup").assertIsDisplayed()
        bar().assertDoesNotExist()
        compose.runOnIdle { assertEquals(Tab.RECIPES, nav.currentDestination.tab()) }

        // Back leaves the import for the Recipes stack it was pushed onto, not for Pantry.
        onNav { popBackStack() }
        compose.onNodeWithText("stub:recipes").assertIsDisplayed()
        tab("Recipes").assertIsSelected()

        // Pantry kept its own place.
        tab("Pantry").performClick()
        compose.onNodeWithText("stub:pantry").assertIsDisplayed()
    }

    @Test
    fun aShareWhileInRecipesPushesOnTopWithoutResettingTheStack() {
        show()
        onNav { navigate(Routes.LISTS) }

        onNav { openRoute(Routes.import("https://example.com/a")) }
        onNav { openRoute(Routes.import("https://example.com/b")) }

        compose.onNodeWithText("stub:import https://example.com/b").assertIsDisplayed()
        onNav { popBackStack() }
        compose.onNodeWithText("stub:import https://example.com/a").assertIsDisplayed()
        onNav { popBackStack() }
        compose.onNodeWithText("stub:lists").assertIsDisplayed()
        compose.runOnIdle { assertTrue(nav.currentDestination.showsTabBar()) }
    }
}
