package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.SharedText
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

    private val inbox = SharedTextInbox()

    /** The route's key, with the text and its reading waiting in the inbox, as MainActivity leaves them (#334). */
    private fun shared(text: String): SavedStateHandle {
        val key = inbox.offer(text, (SharedText.route(text) as SharedText.Route.Editor).reading)
        return SavedStateHandle(mapOf(EditRecipeViewModel.SHARED_TEXT_ARG to key))
    }

    private fun viewModel(text: String, repository: FakeRecipeRepository = FakeRecipeRepository()) =
        EditRecipeViewModel(shared(text), repository, sorting = mainDispatcherRule.dispatcher, sharedTexts = inbox)

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

    @Test fun `the lines the split left out are the note, as written, and saved with the recipe`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { addManualResult = saved(3L) }
            val vm = viewModel(
                "Hi Mum, here's the recipe.\nIngredients:\n- 2 eggs <room temperature>\nMethod:\n1. Beat.\nNotes:\nChill first.",
                repository
            )
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.leftovers)
            assertEquals("Hi Mum, here's the recipe.\nNotes:\nChill first.", state.note)
            assertEquals("2 eggs <room temperature>", state.draft.ingredientsText)

            vm.onNoteChange(state.note + "\nMum's version")
            vm.onDraftChange(vm.uiState.value.draft.copy(name = "Eggs"))
            vm.onSave()
            advanceUntilIdle()

            assertEquals("Hi Mum, here's the recipe.\nNotes:\nChill first.\nMum's version", repository.addManualNotes.single())
        }

    @Test fun `the reading is taken from the inbox, once, and the text kept in saved state for a restart`() =
        runTest(mainDispatcherRule.dispatcher) {
            val text = "Ingredients:\n- 2 cups flour\nMethod:\n1. Mix."
            val handle = shared(text)
            val key = handle.get<String>(EditRecipeViewModel.SHARED_TEXT_ARG)!!
            val vm = EditRecipeViewModel(handle, FakeRecipeRepository(), sorting = mainDispatcherRule.dispatcher, sharedTexts = inbox)
            advanceUntilIdle()
            assertEquals("2 cups flour", vm.uiState.value.draft.ingredientsText)
            assertNull(inbox.take(key))
            assertEquals(text, handle.get<String>(EditRecipeViewModel.SHARED_TEXT_STATE))

            // After process death the inbox is empty and the saved state has the text: read again.
            val restored = SavedStateHandle(
                mapOf(EditRecipeViewModel.SHARED_TEXT_ARG to key, EditRecipeViewModel.SHARED_TEXT_STATE to text)
            )
            val again = EditRecipeViewModel(restored, FakeRecipeRepository(), sorting = mainDispatcherRule.dispatcher, sharedTexts = inbox)
            advanceUntilIdle()
            assertTrue(again.uiState.value.shared)
            assertEquals("2 cups flour", again.uiState.value.draft.ingredientsText)
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
