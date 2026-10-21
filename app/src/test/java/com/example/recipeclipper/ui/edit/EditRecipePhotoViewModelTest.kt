package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.SourceType
import com.example.recipeclipper.data.remote.CheckReason
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.data.remote.LineFlag
import com.example.recipeclipper.data.remote.PhotoBox
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
        EditRecipeViewModel(post(), repository, photoReader = reader, sorting = mainDispatcherRule.dispatcher)

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
            assertEquals(listOf(LineFlag("1 1/2 cups flour", "1 1/2 cups flour", setOf(CheckReason.UNSURE))), state.flags)
            assertTrue(state.checkHeading)
        }

    @Test fun `a line flagged only for its size is named as large, beside the heading a hard-to-read line brings`() =
        runTest(mainDispatcherRule.dispatcher) {
            // #281: "34 tsp salt" for ¾ is read clearly, just too much; "1 1/2 cups flour" was unsure.
            val lines = listOf(PhotoLine("Ingredients"), PhotoLine("34 tsp salt")) + card.drop(1)
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(lines)))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf("34 tsp salt", "1 1/2 cups flour"), state.flags.map { it.raw })
            assertEquals(listOf(true, false), state.flags.map { it.largeOnly })
            assertTrue(state.checkHeading)
        }

    @Test fun `no heading when every line is listed only for its size`() =
        runTest(mainDispatcherRule.dispatcher) {
            val lines = listOf("Ingredients", "34 tsp salt", "12 tbsp vinegar", "1 cup flour", "Directions", "1. Mix.")
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(lines.map { PhotoLine(it, 1f) })))
            advanceUntilIdle()

            assertEquals(listOf("34 tsp salt", "12 tbsp vinegar"), vm.uiState.value.flags.map { it.raw })
            assertFalse(vm.uiState.value.checkHeading)
        }

    @Test fun `a line both misread and too large keeps the heading and isn't named as large`() =
        runTest(mainDispatcherRule.dispatcher) {
            // #283: "11/2" lost its space, and "34 tsp" is too much.
            val lines = listOf("Ingredients", "34 tsp salt, 11/2 tsp pepper", "1 cup flour", "Directions", "1. Mix.")
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(lines.map { PhotoLine(it, 1f) })))
            advanceUntilIdle()

            val flag = vm.uiState.value.flags.single()
            assertEquals(setOf(CheckReason.MISREAD, CheckReason.LARGE), flag.reasons)
            assertFalse(flag.largeOnly)
            assertTrue(vm.uiState.value.checkHeading)
        }

    @Test fun `a chip fixes the line in the box and its one entry, keeping the line's markup`() =
        runTest(mainDispatcherRule.dispatcher) {
            // #283: nothing sorts, so each line stays as read, "▢" and all, and is listed without it.
            val lines = listOf("Grandma's card", "▢ 2% cups oats", "▢ 34 tsp salt")
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(lines.map { PhotoLine(it, 1f) })))
            advanceUntilIdle()
            assertEquals(PhotoOutcome.NOT_SORTED, vm.uiState.value.photoOutcome)
            val oats = vm.uiState.value.flags.first()
            assertEquals(LineFlag("▢ 2% cups oats", "2% cups oats", setOf(CheckReason.MISREAD, CheckReason.FRACTION)), oats)

            vm.onFraction(oats, "⅔")

            val state = vm.uiState.value
            assertEquals("Grandma's card\n▢ 2⅔ cups oats\n▢ 34 tsp salt", state.draft.ingredientsText)
            assertEquals(
                listOf(
                    LineFlag("▢ 2⅔ cups oats", "2⅔ cups oats", setOf(CheckReason.MISREAD)),
                    LineFlag("▢ 34 tsp salt", "34 tsp salt", setOf(CheckReason.LARGE))
                ),
                state.flags
            )
        }

    /** #286: the card is the box's line, so a fix typed in the box shows in the card, whose chips
     *  go with the look-alike; a chip still on screen from before does nothing. */
    @Test fun `a line the cook changes in the box changes in its card`() =
        runTest(mainDispatcherRule.dispatcher) {
            val lines = listOf("Ingredients", "2 % cups oats", "1 cup flour", "Directions", "1. Mix.")
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(lines.map { PhotoLine(it, 1f) })))
            advanceUntilIdle()
            val oats = vm.uiState.value.flags.single()
            vm.onDraftChange(vm.uiState.value.draft.copy(ingredientsText = "2 ¼ cups oats\n1 cup flour"))

            val changed = listOf(LineFlag("2 ¼ cups oats", "2 ¼ cups oats", setOf(CheckReason.MISREAD)))
            assertEquals(changed, vm.uiState.value.flags)
            vm.onFraction(oats, "⅔")
            assertEquals("2 ¼ cups oats\n1 cup flour", vm.uiState.value.draft.ingredientsText)
            assertEquals(changed, vm.uiState.value.flags)
        }

    /** #286: each card has the strip of the picture its line was read from, cut to its box; a line
     *  the reader didn't place, or placed on no picture there is, has none. */
    @Test fun `each card has the strip of the picture its line was read from`() =
        runTest(mainDispatcherRule.dispatcher) {
            val oats = PhotoBox(0.1f, 0.3f, 0.8f, 0.36f)
            val vm = viewModel(FakePhotoTextReader(
                PhotoLine("Ingredients", page = 0, box = PhotoBox(0.1f, 0.1f, 0.5f, 0.15f)),
                PhotoLine("2 % cups oats", page = 1, box = oats),
                PhotoLine("34 tsp salt", page = 0),
                PhotoLine("11/2 cups flour", page = 5, box = oats),
                PhotoLine("Directions"),
                PhotoLine("1. Mix.")
            ))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(2, state.pageCount)
            assertEquals(listOf("2 % cups oats", "34 tsp salt", "11/2 cups flour"), state.checks.map { it.flag.raw })
            assertEquals(listOf(PhotoStrip(back, oats, local = false), null, null), state.checks.map { it.strip })
        }

    /** #286: a card's field is its line in the box: typed over, the box changes, and so does the
     *  card, its chips gone once the look-alike is; its reasons and strip stay. */
    @Test fun `a card's field edits its line in the box`() =
        runTest(mainDispatcherRule.dispatcher) {
            val box = PhotoBox(0.1f, 0.3f, 0.8f, 0.36f)
            val vm = viewModel(FakePhotoTextReader(
                PhotoLine("Ingredients"), PhotoLine("1 cup flour"), PhotoLine("2 % cups oats", page = 0, box = box),
                PhotoLine("Directions"), PhotoLine("1. Mix.")
            ))
            advanceUntilIdle()

            vm.onCheckLine(0, "2 ⅔ cups oats")

            val state = vm.uiState.value
            assertEquals("1 cup flour\n2 ⅔ cups oats", state.draft.ingredientsText)
            assertEquals(LineFlag("2 ⅔ cups oats", "2 ⅔ cups oats", setOf(CheckReason.MISREAD), 0, box), state.checks.single().flag)
            assertEquals(PhotoStrip(front, box, local = false), state.checks.single().strip)
        }

    /** #286: a card follows its line through edits in the box, and goes when the line does. */
    @Test fun `a card follows its line through the box and goes with it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val lines = listOf("Ingredients", "1 cup flour", "2 % cups oats", "Directions", "1. Mix.")
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(lines.map { PhotoLine(it) })))
            advanceUntilIdle()
            vm.onDraftChange(vm.uiState.value.draft.copy(ingredientsText = "1 cup butter\n1 cup flour\n2 % cups oats"))
            vm.onCheckLine(0, "2 ⅔ cups oats")
            assertEquals("1 cup butter\n1 cup flour\n2 ⅔ cups oats", vm.uiState.value.draft.ingredientsText)

            vm.onDraftChange(vm.uiState.value.draft.copy(ingredientsText = "1 cup butter\n1 cup flour"))
            assertTrue(vm.uiState.value.checks.isEmpty())
        }

    @Test fun `a thumbnail opens its picture and closes it again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakePhotoTextReader(PhotoTextResult.Read(card)))
            advanceUntilIdle()
            assertNull(vm.uiState.value.openPage)
            vm.onPage(1)
            assertEquals(1, vm.uiState.value.openPage)
            vm.onPage(0)
            assertEquals(0, vm.uiState.value.openPage)
            vm.onPage(0)
            assertNull(vm.uiState.value.openPage)
        }

    @Test fun `followLine keeps a line through one edit`() {
        val old = listOf("a", "b", "c", "d")
        assertEquals(2, followLine(old, listOf("a", "b", "c", "d"), 2))
        assertEquals("typed over", 1, followLine(old, listOf("a", "B", "c", "d"), 1))
        assertEquals("a line added above", 3, followLine(old, listOf("a", "new", "b", "c", "d"), 2))
        assertEquals("a line taken away above", 2, followLine(old, listOf("a", "c", "d"), 3))
        assertNull("the line itself deleted", followLine(old, listOf("a", "c", "d"), 1))
        assertEquals("split by a new line", 2, followLine(old, listOf("a", "b", "c1", "c2", "d"), 2))
        assertEquals("a line added below", 0, followLine(old, listOf("a", "b", "c", "d", "e"), 0))
    }

    @Test fun `a strip is cut in whole pixels and shown at a readable height`() {
        assertEquals(listOf(0, 0, 100, 50), stripPixels(PhotoBox(0f, 0f, 1f, 1f), 100, 50))
        assertEquals(listOf(50, 25, 51, 26), stripPixels(PhotoBox(0.5f, 0.5f, 0.5f, 0.5f), 100, 50))
        assertEquals("a short line, at most 56 dp", STRIP_MAX, stripHeight(300.dp, 3f))
        assertEquals("a long line, at least 28 dp", STRIP_MIN, stripHeight(300.dp, 30f))
        assertEquals(40.dp, stripHeight(300.dp, 7.5f))
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
