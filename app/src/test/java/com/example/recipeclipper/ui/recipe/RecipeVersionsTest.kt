package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeLink
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recipe versions (#354) on the recipe screen: a version says "Based on <original>" quietly under
 * its title, the original lists its versions after the steps, and a tap opens the other recipe.
 * The real [RecipeScreen] and [RecipeViewModel] over [FakeRecipeRepository].
 */
@RunWith(AndroidJUnit4::class)
class RecipeVersionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val original = Recipe(
        name = "Tomato Soup", image = null, ingredients = listOf("2 cups tomatoes"), instructions = listOf("Simmer."),
        prepTime = null, cookTime = null, totalTime = null, yield = "4 servings",
        sourceUrl = "https://example.com/soup", id = 1L
    )
    private val version = original.copy(
        name = "Spicy Tomato Soup", sourceUrl = "manual:aaaa", id = 2L, origin = ContentOrigin.CHAT
    )

    private val repository = FakeRecipeRepository()
    private val opened = mutableListOf<Long>()

    private fun show(recipe: Recipe, flags: FeatureFlags? = null) {
        repository.openResult = recipe
        val screenModel = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to recipe.id)),
            repository, FakeAppPreferences(), Clock { 0 }, FakeConnectivity(), FakeAppInfo(),
            FakeTimerAlarmScheduler(), featureFlags = flags
        )
        val saveModel = SaveToListViewModel(FakeListRepository())
        compose.setContent {
            RecipeScreen(
                onBack = {}, onOpenRecipe = { opened += it },
                viewModel = screenModel, saveViewModel = saveModel
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(recipe.name)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `a version says what it is based on, and a tap opens the original`() {
        repository.basedOn.value = mapOf(2L to RecipeLink(1L, "Tomato Soup"))
        show(version)

        compose.onNodeWithTag("basedOn").assertIsDisplayed()
        compose.onNodeWithText("Based on Tomato Soup").performClick()
        assertEquals(listOf(1L), opened)
    }

    @Test fun `an original lists its versions after the steps, and a tap opens one`() {
        repository.versions.value = mapOf(
            1L to listOf(RecipeLink(3L, "Even spicier soup"), RecipeLink(2L, "Spicy Tomato Soup"))
        )
        show(original)

        compose.onNodeWithTag("versions").assertExists()
        compose.onNodeWithText("Versions").assertExists()
        compose.onNodeWithText("Spicy Tomato Soup").performClick()
        assertEquals(listOf(2L), opened)
        compose.onNodeWithText("Even spicier soup").assertExists()
    }

    @Test fun `a recipe with no links shows neither`() {
        show(original)

        compose.onNodeWithTag("basedOn").assertDoesNotExist()
        compose.onNodeWithTag("versions").assertDoesNotExist()
    }

    @Test fun `with the recipeVersions flag off nothing is shown`() {
        repository.basedOn.value = mapOf(2L to RecipeLink(1L, "Tomato Soup"))
        val flags = FeatureFlags(FakeFeatureFlagStore(mapOf(Flag.RECIPE_VERSIONS.key to false)), FlagRegistry.definitions, true)
        show(version, flags)

        compose.onNodeWithTag("basedOn").assertDoesNotExist()
        assertTrue(opened.isEmpty())
    }
}
