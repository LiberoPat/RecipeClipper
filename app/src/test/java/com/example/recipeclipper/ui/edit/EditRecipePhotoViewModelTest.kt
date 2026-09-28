package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.SourceType
import com.example.recipeclipper.data.remote.PhotoLine
import com.example.recipeclipper.fake.FakePhotoTextReader
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Read the photo" (#198): the editor over a fake reader. iOS: `EditRecipePhotoTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditRecipePhotoViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val postUrl = "https://www.reddit.com/r/Old_Recipes/comments/1f6d4ef/aunt_junes_oatmeal_cookies/"
    private val front = "https://preview.redd.it/front.jpg"
    private val back = "https://preview.redd.it/back.jpg"

    private val card = listOf(
        PhotoLine("Ingredients", 1f),
        PhotoLine("1 cup butter", 1f),
        PhotoLine("1 1/2 cups flour", 0.3f),
        PhotoLine("Directions", 1f),
        PhotoLine("1. Cream the butter.", 1f),
        PhotoLine("2. Stir in the flour.", 1f)
    )

    private fun post() = SavedStateHandle(
        mapOf(
            EditRecipeViewModel.PHOTO_URL_ARG to postUrl,
            EditRecipeViewModel.PHOTO_TITLE_ARG to "Aunt June's oatmeal cookies",
            EditRecipeViewModel.PHOTO_IMAGES_ARG to "$front\n$back"
        )
    )

    private fun viewModel(reader: FakePhotoTextReader, repository: FakeRecipeRepository = FakeRecipeRepository()) =
        EditRecipeViewModel(post(), repository, photoReader = reader)

    @Test fun `reads every picture in order and fills the editor, sorted, with unsure lines marked`() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakePhotoTextReader(PhotoTextResult.Read(card))
            val vm = viewModel(reader)
            advanceUntilIdle()

            assertEquals(listOf(listOf(front, back)), reader.calls)
            val state = vm.uiState.value
            assertTrue(state.isNew)
            assertFalse(state.reading)
            assertEquals(PhotoOutcome.READ, state.photoOutcome)
            assertEquals("Aunt June's oatmeal cookies", state.draft.name)
            assertEquals(front, state.draft.image)
            assertEquals("1 cup butter\n1 1/2 cups flour", state.draft.ingredientsText)
            assertEquals("Cream the butter.\nStir in the flour.", state.draft.instructionsText)
            assertEquals(listOf("1 1/2 cups flour"), state.uncertain)
        }

    @Test fun `nothing is saved until the cook saves, then under the post's link`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(card)), repository)
            advanceUntilIdle()
            assertTrue(repository.saveClipCalls.isEmpty())

            vm.onDraftChange(vm.uiState.value.draft.copy(ingredientsText = "1 cup butter\n1 1/4 cups flour"))
            vm.onSave()
            advanceUntilIdle()

            val saved = repository.saveClipCalls.single()
            assertEquals(postUrl, saved.sourceUrl)
            assertEquals(SourceType.REDDIT, saved.sourceType)
            assertEquals("Aunt June's oatmeal cookies", saved.name)
            assertEquals(front, saved.image)
            assertEquals(listOf("1 cup butter", "1 1/4 cups flour"), saved.ingredients)
            assertEquals(listOf("Cream the butter.", "Stir in the flour."), saved.instructions)
            assertEquals("en", saved.language)
            assertEquals(1L, vm.uiState.value.savedId)
        }

    @Test fun `lines that don't sort open the editor to finish by hand, every line kept`() =
        runTest(mainDispatcherRule.dispatcher) {
            val lines = listOf(PhotoLine("Cream butter and sugar,"), PhotoLine("add the oats. Bake."))
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(lines)))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(PhotoOutcome.NOT_SORTED, state.photoOutcome)
            assertEquals("Cream butter and sugar,\nadd the oats. Bake.", state.draft.ingredientsText)
            assertEquals("", state.draft.instructionsText)
            assertEquals("Aunt June's oatmeal cookies", state.draft.name)
            assertEquals(front, state.draft.image)
        }

    @Test fun `a photo with no text leaves the title and photo to finish by hand`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(emptyList())), repository)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(PhotoOutcome.NOT_SORTED, state.photoOutcome)
            assertEquals("Aunt June's oatmeal cookies", state.draft.name)
            assertEquals("", state.draft.ingredientsText)

            // Not a recipe yet: Save points out the rule and saves nothing.
            vm.onSave()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.showInvalid)
            assertTrue(repository.saveClipCalls.isEmpty())
        }

    @Test fun `a picture that won't load offers Try again, which reads again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakePhotoTextReader(PhotoTextResult.Failed)
            val vm = viewModel(reader)
            advanceUntilIdle()
            assertEquals(PhotoOutcome.FAILED, vm.uiState.value.photoOutcome)
            assertEquals("Aunt June's oatmeal cookies", vm.uiState.value.draft.name)

            reader.result = PhotoTextResult.Read(card)
            vm.onReadAgain()
            advanceUntilIdle()

            assertEquals(2, reader.calls.size)
            assertEquals(PhotoOutcome.READ, vm.uiState.value.photoOutcome)
        }

    @Test fun `a reader that isn't ready yet says so, keeps the editor, and Try again reads again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakePhotoTextReader(PhotoTextResult.NotReady)
            val vm = viewModel(reader)
            advanceUntilIdle()
            assertEquals(PhotoOutcome.NOT_READY, vm.uiState.value.photoOutcome)
            assertEquals(false, vm.uiState.value.reading)
            assertEquals("Aunt June's oatmeal cookies", vm.uiState.value.draft.name)

            reader.result = PhotoTextResult.Read(card)
            vm.onReadAgain()
            advanceUntilIdle()

            assertEquals(2, reader.calls.size)
            assertEquals(PhotoOutcome.READ, vm.uiState.value.photoOutcome)
        }

    @Test fun `while reading, Save waits, and leaving cancels the read with nothing saved`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val reader = FakePhotoTextReader(PhotoTextResult.Read(card)).apply { gate = CompletableDeferred() }
            val vm = viewModel(reader, repository)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.reading)

            vm.onSave()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.saving)

            ViewModelStore().apply { put("edit", vm) }.clear() // Back: the screen's ViewModel goes
            reader.gate?.complete(Unit)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.reading)
            assertNull(vm.uiState.value.photoOutcome)
            assertTrue(repository.saveClipCalls.isEmpty())
        }

    @Test fun `a full free library keeps the editor open with the prompt`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(card)), repository)
            advanceUntilIdle()
            val shown = vm.uiState.value.draft.applyTo(
                com.example.recipeclipper.data.model.Recipe(
                    name = "", image = null, ingredients = emptyList(), instructions = emptyList(),
                    prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = postUrl
                )
            )
            repository.saveClipResult = ParseResult.Success(shown, kept = false)

            vm.onSave()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.libraryFull)
            assertNull(vm.uiState.value.savedId)
        }

    @Test fun `a failed save says so and keeps the editor`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { saveClipResult = ParseResult.Error(ParseError.SaveFailed) }
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(card)), repository)
            advanceUntilIdle()

            vm.onSave()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.saveFailed)
            assertNull(vm.uiState.value.savedId)
        }
}
