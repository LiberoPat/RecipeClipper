package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Text shared in (#306): the review, split or as written. iOS: `EditRecipeTextTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditRecipeTextViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun shared(text: String) = SavedStateHandle(mapOf(EditRecipeViewModel.SHARED_TEXT_ARG to text))

    private fun viewModel(text: String, repository: FakeRecipeRepository = FakeRecipeRepository()) =
        EditRecipeViewModel(shared(text), repository, sorting = mainDispatcherRule.dispatcher)

    private fun saved(id: Long) = Recipe(
        id = id, name = "Scones", image = null, ingredients = listOf("2 cups flour"), instructions = emptyList(),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "manual:abc"
    )

    @Test fun `a recipe in the text is split into the fields, with no name guessed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel("Grandma's scones\nServes 4\nIngredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix.\n2. Bake.")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.shared)
            assertTrue(state.isNew)
            assertFalse(state.loading)
            assertEquals(true, state.sharedSorted)
            assertNull(state.photo)
            assertEquals("", state.draft.name)
            assertEquals("Serves 4", state.draft.yield)
            assertEquals("2 cups flour\n1 tsp salt", state.draft.ingredientsText)
            assertEquals("Mix.\nBake.", state.draft.instructionsText)
        }

    @Test fun `text that doesn't split is every line a step, as written`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel("Hi Mum,\nMix the flour with the butter and bake it.\n\nLove, Sam")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(false, state.sharedSorted)
            assertEquals("", state.draft.ingredientsText)
            assertEquals("Hi Mum,\nMix the flour with the butter and bake it.\nLove, Sam", state.draft.instructionsText)
        }

    @Test fun `nothing is saved until Save, then it is the cook's own in the text's language`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { addManualResult = saved(7L) }
            val vm = viewModel(
                "Zutaten:\n- 500 g Mehl\n- 2 EL Zucker\n\nZubereitung:\n1. Mehl und Zucker mischen.\n2. Backen.",
                repository
            )
            advanceUntilIdle()
            assertTrue(repository.addManualCalls.isEmpty())

            vm.onDraftChange(vm.uiState.value.draft.copy(name = "Kuchen"))
            vm.onSave()
            advanceUntilIdle()

            assertEquals("Kuchen", repository.addManualCalls.single().name)
            assertEquals("de", repository.addManualLanguages.single())
            assertEquals(7L, vm.uiState.value.savedId)
        }

    @Test fun `with no name, Save points out the rule and saves nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val vm = viewModel("Ingredients:\n- 2 cups flour\nMethod:\n1. Mix.", repository)
            advanceUntilIdle()

            vm.onSave()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.showInvalid)
            assertTrue(repository.addManualCalls.isEmpty())
        }
}
