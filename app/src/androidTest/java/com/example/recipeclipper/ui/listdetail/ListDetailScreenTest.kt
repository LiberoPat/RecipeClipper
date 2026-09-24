package com.example.recipeclipper.ui.listdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeListRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rule this screen exists to enforce is "Favorites is the only list you can't delete", and
 * it is enforced in two places: the SQL, covered by `ListDaoTest`, and whether the menu item is
 * even offered, which is here. Both matter — a Delete that silently does nothing is as bad as
 * one that shouldn't have been there.
 */
@RunWith(AndroidJUnit4::class)
class ListDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val favorites = RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0)
    private val lunch = RecipeList(2, "Lunch", isBuiltIn = true, isFavorites = false, recipeCount = 0)
    private val weeknights = RecipeList(3, "Weeknights", isBuiltIn = false, isFavorites = false, recipeCount = 0)

    private val repository = FakeListRepository().apply {
        lists.value = listOf(favorites, lunch, weeknights)
    }

    private class Taps {
        var back = 0
        var recipe: Long? = null
    }

    private fun show(listId: Long): Taps {
        val taps = Taps()
        val viewModel = ListDetailViewModel(
            repository,
            SavedStateHandle(mapOf(ListDetailViewModel.LIST_ID_ARG to listId))
        )
        compose.setContent {
            ListDetailScreen(
                onBack = { taps.back++ },
                onOpenRecipe = { taps.recipe = it },
                viewModel = viewModel
            )
        }
        return taps
    }

    private fun openMenu() = compose.onNodeWithContentDescription("More options").performClick()

    @Test
    fun theListNameIsTheHeading() {
        show(weeknights.id)

        compose.onNodeWithText("Weeknights").assertIsDisplayed()
    }

    @Test
    fun anEmptyListSaysSoRatherThanLookingBroken() {
        show(weeknights.id)

        compose.onNodeWithText("Nothing in this list yet.").assertIsDisplayed()
    }

    @Test
    fun theRecipesInTheListAreShown() {
        repository.recipesIn.value = listOf(
            RecipeSummary(7, "Adobo", null, null, 1, isSaved = true),
            RecipeSummary(8, "Carbonara", null, null, 2, isSaved = true)
        )
        show(weeknights.id)

        compose.onNodeWithText("Adobo").assertIsDisplayed()
        compose.onNodeWithText("Carbonara").assertIsDisplayed()
        compose.onNodeWithText("Nothing in this list yet.").assertDoesNotExist()
    }

    @Test
    fun tappingARecipeOpensIt() {
        repository.recipesIn.value = listOf(RecipeSummary(7, "Adobo", null, null, 1, isSaved = true))
        val taps = show(weeknights.id)

        compose.onNodeWithText("Adobo").performClick()

        assertEquals(7L, taps.recipe)
    }

    // --- What the overflow menu offers ---

    @Test
    fun favoritesOffersRenameButNotDelete() {
        show(favorites.id)

        openMenu()

        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete list").assertDoesNotExist()
    }

    /** Lunch is seeded like Favorites, but only Favorites is protected. */
    @Test
    fun aSeededListThatIsNotFavoritesOffersDelete() {
        show(lunch.id)

        openMenu()

        compose.onNodeWithText("Delete list").assertIsDisplayed()
    }

    @Test
    fun aUserListOffersDelete() {
        show(weeknights.id)

        openMenu()

        compose.onNodeWithText("Delete list").assertIsDisplayed()
    }

    // --- Deleting ---

    @Test
    fun deletingAsksFirstAndNamesTheList() {
        show(weeknights.id)

        openMenu()
        compose.onNodeWithText("Delete list").performClick()

        compose.onNodeWithText("Delete \"Weeknights\"?").assertIsDisplayed()
        // The dialog says what does NOT happen, which is the part worth being sure of.
        compose.onNodeWithText("The list is removed. The recipes in it stay in your history.")
            .assertIsDisplayed()
        assertTrue(repository.deleteCalls.isEmpty())
    }

    @Test
    fun cancellingTheConfirmationDeletesNothing() {
        show(weeknights.id)

        openMenu()
        compose.onNodeWithText("Delete list").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(repository.deleteCalls.isEmpty())
    }

    @Test
    fun confirmingDeletesAndLeavesTheScreen() {
        val taps = show(weeknights.id)

        openMenu()
        compose.onNodeWithText("Delete list").performClick()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(listOf(weeknights.id), repository.deleteCalls)
        compose.waitUntil { taps.back == 1 }
    }

    // --- Renaming ---

    @Test
    fun renamingOpensPreFilledSoItIsAnEditNotARetype() {
        show(weeknights.id)

        openMenu()
        compose.onNodeWithText("Rename").performClick()

        compose.onNodeWithText("Rename list").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Weeknights")
    }

    @Test
    fun renamingWritesTheNewName() {
        show(weeknights.id)

        openMenu()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Midweek")
        compose.onNodeWithText("Save").performClick()

        assertEquals(listOf(weeknights.id to "Midweek"), repository.renameCalls)
        compose.onNodeWithText("Midweek").assertIsDisplayed()
    }

    @Test
    fun favoritesCanBeRenamed() {
        show(favorites.id)

        openMenu()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Best of")
        compose.onNodeWithText("Save").performClick()

        assertEquals(listOf(favorites.id to "Best of"), repository.renameCalls)
    }

    @Test
    fun cancellingARenameChangesNothing() {
        show(weeknights.id)

        openMenu()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(repository.renameCalls.isEmpty())
        compose.onNodeWithText("Weeknights").assertIsDisplayed()
    }
}
