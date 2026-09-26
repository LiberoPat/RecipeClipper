package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "I made this" (#116): adding opens the new photo; its note waits for a pause; Undo for a delete. */
@OptIn(ExperimentalCoroutinesApi::class)
class CookedPhotosViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val photos = FakeCookedPhotoRepository()

    private fun kotlinx.coroutines.test.TestScope.viewModel() = CookedPhotosViewModel(photos).also {
        collectEagerly(it.uiState)
        it.setRecipe(7)
    }

    @Test fun `added pictures become photos, the first opens, and a broken one says so`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.onAdd(listOf("content://a", "bad://b", "content://c"))
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.photos.size)
        assertEquals(1L, vm.uiState.value.open?.id)
        assertTrue(vm.uiState.value.addFailed)
        vm.onAddFailedShown()
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.addFailed)
    }

    @Test fun `only this recipe's photos show`() = runTest(mainDispatcherRule.dispatcher) {
        photos.photo(recipeId = 7)
        photos.photo(recipeId = 8)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf(7L), vm.uiState.value.photos.map { it.recipeId })
    }

    @Test fun `the note is written once typing pauses, trimmed, and at once on close`() = runTest(mainDispatcherRule.dispatcher) {
        val photo = photos.photo(recipeId = 7)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onOpen(photo)
        vm.onNoteChange("Less")
        vm.onNoteChange("Less sugar ")
        advanceTimeBy(CookedPhotosViewModel.NOTE_DELAY_MILLIS + 1)
        assertEquals(listOf(Triple(photo.id, photo.day, "Less sugar")), photos.edits)

        vm.onNoteChange("Less sugar, more salt")
        vm.onClose()
        advanceUntilIdle()
        assertEquals("Less sugar, more salt", photos.edits.last().third)
        assertNull(vm.uiState.value.open)
    }

    @Test fun `a new day keeps the note`() = runTest(mainDispatcherRule.dispatcher) {
        val photo = photos.photo(recipeId = 7, note = "Good")
        val vm = viewModel()
        advanceUntilIdle()
        vm.onOpen(photo)
        vm.onDayChange(19_990)
        advanceUntilIdle()
        assertEquals(Triple(photo.id, 19_990L, "Good"), photos.edits.last())
        assertEquals(19_990L, vm.uiState.value.open?.day)
    }

    @Test fun `a deleted photo comes back with Undo, and its file goes only once the delete stands`() =
        runTest(mainDispatcherRule.dispatcher) {
            val photo = photos.photo(recipeId = 7)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onOpen(photo)
            vm.onDelete()
            advanceUntilIdle()
            assertEquals(photo, vm.uiState.value.deleted)
            assertTrue(vm.uiState.value.photos.isEmpty())
            vm.onUndoDelete()
            advanceUntilIdle()
            assertEquals(listOf(photo.id), vm.uiState.value.photos.map { it.id })
            assertTrue(photos.forgotten.isEmpty())

            vm.onOpen(photo)
            vm.onDelete()
            advanceUntilIdle()
            vm.onDeleteSettled()
            advanceUntilIdle()
            assertEquals(listOf(photo.fileName), photos.forgotten)
        }
}
