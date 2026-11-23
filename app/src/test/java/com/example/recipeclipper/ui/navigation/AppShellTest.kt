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
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.ui.listdetail.ListDetailViewModel
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import com.example.recipeclipper.ui.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        composable(Routes.settingsSection(SettingsSection.UNITS)) { Text("stub:settings units") }
        composable(Routes.LISTS) { Text("stub:lists") }
        composable(
            Routes.LIST_DETAIL,
            arguments = listOf(navArgument(ListDetailViewModel.LIST_ID_ARG) { type = NavType.LongType })
        ) { Text("stub:list ${it.arguments?.getLong(ListDetailViewModel.LIST_ID_ARG)}") }
        composable(
            Routes.RECIPE,
            arguments = listOf(
                navArgument(RecipeViewModel.RECIPE_ID_ARG) { type = NavType.LongType },
                navArgument(RecipeViewModel.SERVINGS_ARG) { type = NavType.IntType; defaultValue = 0 },
                navArgument(RecipeViewModel.START_COOK_ARG) { type = NavType.BoolType; defaultValue = false }
            )
        ) {
            // The Today card's meal (#375) carries its servings and a start-cooking flag.
            val servings = it.arguments?.getInt(RecipeViewModel.SERVINGS_ARG)?.takeIf { s -> s > 0 }
            val start = it.arguments?.getBoolean(RecipeViewModel.START_COOK_ARG) == true
            Text(
                "stub:recipe ${it.arguments?.getLong(RecipeViewModel.RECIPE_ID_ARG)}" +
                    (servings?.let { s -> " x$s" } ?: "") + if (start) " start" else ""
            )
        }
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
        composable(
            Routes.WEEK_RECIPE,
            arguments = listOf(
                navArgument(RecipeViewModel.RECIPE_ID_ARG) { type = NavType.LongType },
                navArgument(RecipeViewModel.SERVINGS_ARG) { type = NavType.IntType; defaultValue = 0 }
            )
        ) { Text("stub:week recipe ${it.arguments?.getLong(RecipeViewModel.RECIPE_ID_ARG)}") }
    }

    /** And the Groceries tab's (#50). */
    private fun NavGraphBuilder.stubGroceries() {
        composable(Routes.GROCERIES) { Text("stub:groceries") }
    }

    /** And the Pantry tab's (#51). */
    private fun NavGraphBuilder.stubPantry() {
        composable(Routes.PANTRY) { Text("stub:pantry") }
    }

    /** With [libraryFirst] (#375) the Recipes tab's first screen is the library, not Home. */
    private fun show(libraryFirst: Boolean = false) {
        compose.setContent {
            nav = rememberNavController()
            AppShell(
                nav, recipes = { stubRecipes() }, week = { stubWeek() },
                groceries = { stubGroceries() }, pantry = { stubPantry() }, libraryFirst = libraryFirst
            )
        }
        compose.onNodeWithText(if (libraryFirst) "stub:recipes" else "stub:home").assertIsDisplayed()
    }

    private fun onNav(action: NavHostController.() -> Unit) {
        compose.runOnIdle { nav.action() }
        compose.waitForIdle()
    }

    /** The bar item, not the placeholder title with the same word. */
    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())
    private fun bar() = compose.onNodeWithTag("tabBar")

    /** German at twice the text on a phone: every tab's name keeps one line, whole ("Rezept / e" before #454's fix). */
    @Test
    @org.robolectric.annotation.Config(qualifiers = "de-w360dp-h800dp", fontScale = 2f)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun inGermanAtTwiceTheTextEveryTabNameKeepsOneLineWhole() {
        show(libraryFirst = true)
        for ((tab, name) in listOf("RECIPES" to "Rezepte", "WEEK" to "Plan", "GROCERIES" to "Einkauf", "PANTRY" to "Vorrat")) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("tabLabel-$tab", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val label = layouts.single()
            assertEquals(name, label.layoutInput.text.text)
            assertEquals("$name on one line", 1, label.lineCount)
            assertTrue("$name whole, not ellipsized", !label.isLineEllipsized(0))
            assertTrue("$name inside its slot: ${label.getLineRight(0)} vs ${label.size}", label.getLineRight(0) <= label.size.width + 1f)
        }
    }

    @Test
    fun theBarShowsFourTabsInOrderWithRecipesOpen() {
        show()

        bar().assertIsDisplayed()
        val lefts = listOf("Recipes", "Plan", "Groceries", "Pantry").map {
            tab(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(lefts.sorted(), lefts)
        tab("Recipes").assertIsSelected()
        tab("Plan").assertIsNotSelected()
    }

    @Test
    fun theBarShowsOnTheRecipesListScreensAndHidesOnARecipe() {
        show()

        for (route in listOf(Routes.RECIPES, Routes.LISTS, Routes.list(4), Routes.SETTINGS, Routes.settingsSection(SettingsSection.UNITS))) {
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

        tab("Plan").performClick()
        compose.onNodeWithText("stub:week").assertIsDisplayed()
        tab("Plan").assertIsSelected()

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

        tab("Plan").performClick()
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

    // #397: a reminder's tap opens the planned recipe on the Week's own stack, whichever tab is open.
    @Test
    fun aReminderTapLandsInTheWeekOnItsOwnStack() {
        show()
        tab("Pantry").performClick()
        compose.onNodeWithText("stub:pantry").assertIsDisplayed()

        onNav { openRoute(Routes.weekRecipe(5, null)) }

        compose.onNodeWithText("stub:week recipe 5").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Tab.WEEK, nav.currentDestination.tab()) }
        bar().assertDoesNotExist()

        // Back returns to the week.
        onNav { popBackStack() }
        compose.onNodeWithText("stub:week").assertIsDisplayed()
        tab("Plan").assertIsSelected()
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

    // #333: a countdown tapped while its recipe is on top mustn't stack a second copy.
    @Test
    fun aCountdownTapOnTheRecipeAlreadyOnTopOpensNoSecondCopy() {
        show()
        onNav { navigate(Routes.recipe(5)) }

        onNav { openRoute(Routes.cookRecipe(5)) }

        compose.onNodeWithText("stub:recipe 5").assertIsDisplayed()
        onNav { popBackStack() }
        compose.onNodeWithText("stub:home").assertIsDisplayed()
    }

    @Test
    fun aCountdownTapOnAnotherRecipeStillOpensIt() {
        show()
        onNav { navigate(Routes.recipe(5)) }

        onNav { openRoute(Routes.cookRecipe(7)) }

        compose.onNodeWithText("stub:recipe 7").assertIsDisplayed()
        onNav { popBackStack() }
        compose.onNodeWithText("stub:recipe 5").assertIsDisplayed()
    }

    @Test
    fun theRecipeARouteOpensIsReadFromItAlone() {
        assertEquals(5L, recipeIdIn(Routes.recipe(5)))
        assertEquals(7L, recipeIdIn(Routes.cookRecipe(7)))
        assertNull(recipeIdIn(Routes.import("https://example.com/recipe/5")))
        assertNull(recipeIdIn(Routes.RECIPES))
        assertNull(recipeIdIn("recipe/5/extra"))
    }

    // Library first (#375): the Recipes tab opens the library; Home is not on the stack.

    @Test
    fun withLibraryFirstTheRecipesTabOpensTheLibraryAndHomeIsNeverShown() {
        show(libraryFirst = true)

        compose.onNodeWithText("stub:home").assertDoesNotExist()
        tab("Recipes").assertIsSelected()
        bar().assertIsDisplayed()

        // Another tab and back: still the library.
        tab("Plan").performClick()
        compose.onNodeWithText("stub:week").assertIsDisplayed()
        tab("Recipes").performClick()
        compose.onNodeWithText("stub:recipes").assertIsDisplayed()
        compose.onNodeWithText("stub:home").assertDoesNotExist()
    }

    @Test
    fun withLibraryFirstChoosingTheOpenTabAgainPopsToTheLibrary() {
        show(libraryFirst = true)
        onNav { navigate(Routes.LISTS) }
        onNav { navigate(Routes.list(4)) }
        onNav { navigate(Routes.recipe(7)) }
        bar().assertDoesNotExist()
        onNav { popBackStack() }

        tab("Recipes").performClick()

        compose.onNodeWithText("stub:recipes").assertIsDisplayed()
        tab("Recipes").assertIsSelected()
        // The library is the root: nothing under it.
        compose.runOnIdle { assertEquals(Routes.RECIPES, nav.currentDestination?.route) }
        onNav { navigate(Routes.SETTINGS) }
        onNav { popBackStack() }
        compose.onNodeWithText("stub:recipes").assertIsDisplayed()
    }

    @Test
    fun withLibraryFirstAShareLandsInRecipesOverTheLibraryAndBackReturnsToIt() {
        show(libraryFirst = true)
        tab("Pantry").performClick()
        compose.onNodeWithText("stub:pantry").assertIsDisplayed()

        onNav { openRoute(Routes.import("https://example.com/soup")) }

        compose.onNodeWithText("stub:import https://example.com/soup").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Tab.RECIPES, nav.currentDestination.tab()) }
        bar().assertDoesNotExist()

        onNav { popBackStack() }
        compose.onNodeWithText("stub:recipes").assertIsDisplayed()
        tab("Recipes").assertIsSelected()
    }

    @Test
    fun withLibraryFirstAShareWhileOnARecipeStacksOnTopOfItAndBackReturnsThere() {
        show(libraryFirst = true)
        onNav { navigate(Routes.recipe(5)) }

        onNav { openRoute(Routes.import("https://example.com/soup")) }
        compose.onNodeWithText("stub:import https://example.com/soup").assertIsDisplayed()
        onNav { popBackStack() }
        compose.onNodeWithText("stub:recipe 5").assertIsDisplayed()
        onNav { popBackStack() }
        compose.onNodeWithText("stub:recipes").assertIsDisplayed()
    }

    @Test
    fun withLibraryFirstOpeningTheRecipesRouteGoesBackToTheLibraryNotOnTopOfIt() {
        show(libraryFirst = true)
        onNav { navigate(Routes.LISTS) }

        // A received file's "Recipes" (MainActivity): the library itself, once.
        onNav { openRoute(Routes.RECIPES) }

        compose.onNodeWithText("stub:recipes").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(Routes.RECIPES, nav.currentDestination?.route)
            assertTrue(nav.previousBackStackEntry?.destination?.route != Routes.RECIPES)
        }
    }

    @Test
    fun withHomeStillThereOpeningTheRecipesRoutePushesTheLibraryOverIt() {
        show()

        onNav { openRoute(Routes.RECIPES) }

        compose.onNodeWithText("stub:recipes").assertIsDisplayed()
        onNav { popBackStack() }
        compose.onNodeWithText("stub:home").assertIsDisplayed()
    }

    // The Today card's Start cooking (#375) opens on the Recipes stack, so Back returns to the library.
    @Test
    fun startCookingOpensTheMealOnTheRecipesStackAndBackReturnsToTheLibrary() {
        show(libraryFirst = true)

        onNav { navigate(Routes.plannedRecipe(5, 6, startCook = true)) }

        compose.onNodeWithText("stub:recipe 5 x6 start").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Tab.RECIPES, nav.currentDestination.tab()) }
        bar().assertDoesNotExist()
        onNav { popBackStack() }
        compose.onNodeWithText("stub:recipes").assertIsDisplayed()
        tab("Recipes").assertIsSelected()
    }

    @Test
    fun theCardsRoutesCarryOnlyWhatTheyNeed() {
        assertEquals("recipe/5", Routes.plannedRecipe(5, null))
        assertEquals("recipe/5?servings=4", Routes.plannedRecipe(5, 4))
        assertEquals("recipe/5?servings=4&startCook=true", Routes.plannedRecipe(5, 4, startCook = true))
        assertEquals("recipe/5?startCook=true", Routes.plannedRecipe(5, null, startCook = true))
        assertEquals(5L, recipeIdIn(Routes.plannedRecipe(5, 4, startCook = true)))
    }
}
