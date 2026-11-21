package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.SharedText
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The editor and a recipe's tips (#432): a box below the steps, only for a recipe that has them. iOS: `EditRecipeTipsTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditRecipeTipsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun flags(tipsOn: Boolean) =
        FeatureFlags(FakeFeatureFlagStore(mapOf(Flag.RECIPE_TIPS.key to tipsOn)), FlagRegistry.definitions, isDebug = false)

    private val pies = Recipe(
        name = "Mini Apple Pies", image = null, ingredients = listOf("1 apple"), instructions = listOf("Bake."),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://example.com/pies", id = 3L,
        tips = listOf("Use a stone.", "Or a steel.")
    )

    private fun editing(id: Long) = SavedStateHandle(mapOf(EditRecipeViewModel.RECIPE_ID_ARG to id))

    @Test fun `a recipe with tips opens with its tips box, one tip per line`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = EditRecipeViewModel(editing(3L), FakeRecipeRepository().apply { openResult = pies })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showsTips)
        assertEquals("Use a stone.\nOr a steel.", vm.uiState.value.draft.tipsText)
        assertEquals("Bake.", vm.uiState.value.draft.instructionsText)
    }

    @Test fun `a recipe without tips, and a new one, have no tips box`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = EditRecipeViewModel(editing(3L), FakeRecipeRepository().apply { openResult = pies.copy(tips = emptyList()) })
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showsTips)
        assertFalse(EditRecipeViewModel(SavedStateHandle(), FakeRecipeRepository()).uiState.value.showsTips)
    }

    private val inbox = SharedTextInbox()

    private fun chat(tipsOn: Boolean): EditRecipeViewModel {
        val text = "```json\n{\"@type\":\"Recipe\",\"name\":\"Pies\",\"recipeIngredient\":[\"1 apple\"]," +
            "\"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"Bake.\"},{\"@type\":\"HowToTip\",\"text\":\"Use a stone.\"}]}\n```"
        val key = inbox.offer(text, (SharedText.route(text) as SharedText.Route.Editor).reading)
        return EditRecipeViewModel(
            SavedStateHandle(mapOf(EditRecipeViewModel.SHARED_TEXT_ARG to key)), FakeRecipeRepository(),
            sorting = mainDispatcherRule.dispatcher, sharedTexts = inbox, flags = flags(tipsOn)
        )
    }

    @Test fun `a chat's HowToTip fills the tips box with recipeTips on`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = chat(tipsOn = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showsTips)
        assertEquals("Bake.", vm.uiState.value.draft.instructionsText)
        assertEquals("Use a stone.", vm.uiState.value.draft.tipsText)
    }

    @Test fun `with recipeTips off a chat's HowToTip is the last step, as before`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = chat(tipsOn = false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showsTips)
        assertEquals("Bake.\nUse a stone.", vm.uiState.value.draft.instructionsText)
        assertEquals("", vm.uiState.value.draft.tipsText)
    }
}
