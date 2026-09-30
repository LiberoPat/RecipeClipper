package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.PhotoLine
import com.example.recipeclipper.fake.FakePhotoTextReader
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

/** "Scan a recipe" (#226): the review over a fake reader. iOS: `EditRecipeScanTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditRecipeScanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val front = "content://media/picker/0/com.android.providers.media.photopicker/media/1000000031"
    private val back = "content://com.example.recipeclipper.fileprovider/camera/scan-1727600000000.jpg"

    private val card = listOf(
        PhotoLine("Ingredients", 1f),
        PhotoLine("1 cup butter", 1f),
        PhotoLine("11/2 cups flour", 1f),
        PhotoLine("Directions", 1f),
        PhotoLine("1. Cream the butter.", 1f),
        PhotoLine("2. Stir in the flour.", 0.3f)
    )

    /** The German card of `shared/fixtures/languages/de.json`, as a photo's lines. */
    private val germanCard = listOf(
        "Omas Apfelkuchen", "ZUTATEN", "200 g Butter", "150 g Zucker", "3 Eier", "300 g Mehl",
        "1 Pck. Backpulver", "4 Äpfel", "ZUBEREITUNG:", "Butter und Zucker schaumig rühren.",
        "Eier nacheinander unterrühren.", "Mehl mit Backpulver mischen und mit der Milch unterrühren.",
        "Bei 180 °C etwa 45 Minuten backen."
    ).map { PhotoLine(it, 1f) }

    private fun scan(vararg pages: String) =
        SavedStateHandle(mapOf(EditRecipeViewModel.SCAN_PAGES_ARG to pages.joinToString("\n")))

    private fun saved(id: Long) = Recipe(
        id = id, name = "Cookies", image = null, ingredients = listOf("1 cup butter"), instructions = emptyList(),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "manual:abc"
    )

    @Test fun `reads the pages in order, sorted, with suspect amounts and unsure lines listed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakePhotoTextReader(PhotoTextResult.Read(card))
            val vm = EditRecipeViewModel(scan(front, back), FakeRecipeRepository(), photoReader = reader)
            advanceUntilIdle()

            assertEquals(listOf(listOf(front, back)), reader.calls)
            val state = vm.uiState.value
            assertTrue(state.scan)
            assertTrue(state.isNew)
            assertEquals(listOf(front, back), state.photo?.imageUrls)
            assertEquals(PhotoOutcome.READ, state.photoOutcome)
            // No title is guessed, and a scan's page is never the recipe's picture.
            assertEquals("", state.draft.name)
            assertEquals("", state.draft.image)
            assertEquals("1 cup butter\n11/2 cups flour", state.draft.ingredientsText)
            assertEquals("Cream the butter.\nStir in the flour.", state.draft.instructionsText)
            assertEquals(listOf("11/2 cups flour", "Stir in the flour."), state.uncertain)
        }

    @Test fun `nothing is saved until the cook saves, then as a typed-in recipe with no picture`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { addManualResult = saved(7) }
            val vm = EditRecipeViewModel(scan(front), repository, photoReader = FakePhotoTextReader(PhotoTextResult.Read(card)))
            advanceUntilIdle()
            assertTrue(repository.addManualCalls.isEmpty())

            vm.onDraftChange(vm.uiState.value.draft.copy(name = "Cookies", ingredientsText = "1 cup butter\n1 1/2 cups flour"))
            vm.onSave()
            advanceUntilIdle()

            val draft = repository.addManualCalls.single()
            assertEquals("Cookies", draft.name)
            assertEquals("", draft.image)
            assertEquals(listOf("1 cup butter", "1 1/2 cups flour"), draft.ingredients)
            assertEquals(listOf("en"), repository.addManualLanguages)
            assertTrue(repository.saveClipCalls.isEmpty())
            assertEquals(7L, vm.uiState.value.savedId)
        }

    @Test fun `a German card is saved in German, as its words say`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { addManualResult = saved(3) }
        val vm = EditRecipeViewModel(scan(front), repository, photoReader = FakePhotoTextReader(PhotoTextResult.Read(germanCard)))
        advanceUntilIdle()
        assertEquals(PhotoOutcome.READ, vm.uiState.value.photoOutcome)

        vm.onDraftChange(vm.uiState.value.draft.copy(name = "Omas Apfelkuchen"))
        vm.onSave()
        advanceUntilIdle()

        assertEquals(listOf("de"), repository.addManualLanguages)
    }

    @Test fun `nothing read opens the review to finish by hand`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val lines = listOf(PhotoLine("Cream butter and sugar,"), PhotoLine("add the oats. Bake."))
        val vm = EditRecipeViewModel(scan(front), repository, photoReader = FakePhotoTextReader(PhotoTextResult.Read(lines)))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(PhotoOutcome.NOT_SORTED, state.photoOutcome)
        assertEquals("Cream butter and sugar,\nadd the oats. Bake.", state.draft.ingredientsText)

        // Not a recipe yet (no name): Save points out the rule and saves nothing.
        vm.onSave()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showInvalid)
        assertTrue(repository.addManualCalls.isEmpty())
    }

    @Test fun `the reader not ready says so, and Try again reads the same pages`() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakePhotoTextReader(PhotoTextResult.NotReady)
            val vm = EditRecipeViewModel(scan(front, back), FakeRecipeRepository(), photoReader = reader)
            advanceUntilIdle()
            assertEquals(PhotoOutcome.NOT_READY, vm.uiState.value.photoOutcome)
            assertFalse(vm.uiState.value.reading)

            reader.result = PhotoTextResult.Read(card)
            vm.onReadAgain()
            advanceUntilIdle()

            assertEquals(listOf(listOf(front, back), listOf(front, back)), reader.calls)
            assertEquals(PhotoOutcome.READ, vm.uiState.value.photoOutcome)
        }

    @Test fun `a page that can't be opened fails, with Try again`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = EditRecipeViewModel(scan(front), FakeRecipeRepository(), photoReader = FakePhotoTextReader(PhotoTextResult.Failed))
        advanceUntilIdle()
        assertEquals(PhotoOutcome.FAILED, vm.uiState.value.photoOutcome)
        assertTrue(vm.uiState.value.scan)
    }

    @Test fun `no pages is a plain new recipe`() = runTest(mainDispatcherRule.dispatcher) {
        val reader = FakePhotoTextReader()
        val vm = EditRecipeViewModel(scan(), FakeRecipeRepository(), photoReader = reader)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.scan)
        assertNull(vm.uiState.value.photo)
        assertTrue(reader.calls.isEmpty())
    }
}
