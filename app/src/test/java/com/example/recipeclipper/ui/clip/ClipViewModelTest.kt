package com.example.recipeclipper.ui.clip

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.ClipDraftStore
import com.example.recipeclipper.data.model.ClipDraft
import com.example.recipeclipper.data.model.ClipField
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
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

@OptIn(ExperimentalCoroutinesApi::class)
class ClipViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val shared = "https://www.hearthandcrumb.example/cookies?utm_source=x#jump"
    private val cleaned = "https://www.hearthandcrumb.example/cookies"

    private val repository = FakeRecipeRepository()
    private val store = ClipDraftStore()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle(mapOf(ClipViewModel.URL_ARG to shared))) =
        ClipViewModel(handle, repository, store)

    /** Field first (#237): arms [field] (unless it is armed), selects [text] on the page, confirms. */
    private fun ClipViewModel.select(text: String, field: ClipField) {
        if (uiState.value.armed != field) onFieldButton(field)
        onSelectionChanged(text)
        onConfirm()
    }

    private val ClipViewModel.draft get() = uiState.value.draft
    private val ClipViewModel.message get() = uiState.value.notice?.message
    private val ClipViewModel.hint get() = uiState.value.hint

    @Test fun `the page is the cleaned link`() {
        val vm = viewModel()
        assertEquals(cleaned, vm.uiState.value.url)
        assertEquals(cleaned, vm.draft.sourceUrl)
        assertNull(vm.uiState.value.notice)
    }

    @Test fun `opened in the import's place by reddit's block, it says so`() {
        assertFalse(viewModel().uiState.value.readBlocked)
        val blocked = viewModel(SavedStateHandle(mapOf(ClipViewModel.URL_ARG to shared, ClipViewModel.BLOCKED_ARG to true)))
        assertTrue(blocked.uiState.value.readBlocked)
    }

    @Test fun `the page loads from where the link points, except other reddit hosts, which load from www`() {
        assertEquals(cleaned, viewModel().uiState.value.pageUrl)
        val old = "https://old.reddit.com/r/recipes/comments/1abc01/apple_pie/"
        val vm = viewModel(SavedStateHandle(mapOf(ClipViewModel.URL_ARG to old, ClipViewModel.BLOCKED_ARG to true)))
        assertEquals("https://www.reddit.com/r/recipes/comments/1abc01/apple_pie/", vm.uiState.value.pageUrl)
        // The clip is still saved under the link that was shared.
        assertEquals(old, vm.uiState.value.url)
        assertEquals(old, vm.draft.sourceUrl)
    }

    @Test fun `a selection is previewed as the lines it would become`() {
        val vm = viewModel()
        vm.onSelectionChanged("1 cup flour\n\n 2 eggs ")
        assertEquals(listOf("1 cup flour", "2 eggs"), vm.uiState.value.selection)
    }

    // --- Field first (#237) ---

    // --- Guided steps (#251) ---

    @Test fun `it opens on step 1, Name armed, with the page's text outlined`() {
        val state = viewModel().uiState.value
        assertEquals(ClipField.NAME, state.armed)
        assertEquals(ClipHint.Step(ClipField.NAME, 1, added = null), state.hint)
        assertEquals(ClipOutline.TEXT, state.outline)
        assertFalse(state.guideDone)
    }

    @Test fun `the steps run in order, each confirm arming the next, then the Done prompt`() {
        val vm = viewModel()
        vm.onSelectionChanged("Cookies")
        vm.onConfirm()
        assertEquals(ClipHint.Step(ClipField.INGREDIENTS, 2, ClipAdded(ClipField.NAME, 1)), vm.hint)
        vm.onSelectionChanged("flour\nsugar")
        vm.onConfirm()
        assertEquals(ClipHint.Step(ClipField.STEPS, 3, ClipAdded(ClipField.INGREDIENTS, 2)), vm.hint)
        vm.onSelectionChanged("Mix.\nBake.")
        vm.onConfirm()
        assertEquals(ClipHint.Step(ClipField.PHOTO, 4, ClipAdded(ClipField.STEPS, 2)), vm.hint)
        // On Photo, the images are outlined.
        assertEquals(ClipOutline.IMAGES, vm.uiState.value.outline)
        vm.onImageTapped("https://img.example/cookies.jpg")

        val state = vm.uiState.value
        assertNull(state.armed)
        assertNull(state.outline)
        assertTrue(state.guideDone)
        assertEquals(ClipHint.Next(ClipAdded(ClipField.PHOTO, 1), null), state.hint)
    }

    @Test fun `Skip moves to the next step, and Skip on Photo ends the steps`() {
        val vm = viewModel()
        vm.onSkip()
        assertEquals(ClipField.INGREDIENTS, vm.uiState.value.armed)
        assertEquals(setOf(ClipField.NAME), vm.uiState.value.skipped)
        vm.onSkip()
        vm.onSkip()
        assertEquals(ClipHint.Step(ClipField.PHOTO, 4, null), vm.hint)
        vm.onSkip()

        assertNull(vm.uiState.value.armed)
        assertTrue(vm.uiState.value.guideDone)
        // Nothing was added: the prompt points back to the first empty field.
        assertTrue(vm.draft.isEmpty)
        assertNull(vm.uiState.value.notice)
    }

    @Test fun `a step skipped is passed over when the steps move on`() {
        val vm = viewModel()
        vm.onSkip() // Name
        vm.select("flour", ClipField.INGREDIENTS)
        vm.select("Bake.", ClipField.STEPS)
        assertEquals(ClipField.PHOTO, vm.uiState.value.armed)
        vm.onSkip()
        // The name is still empty but was skipped: the steps are over, Done asks for it.
        assertNull(vm.uiState.value.armed)
        assertTrue(vm.uiState.value.guideDone)
    }

    @Test fun `a field button jumps there, making it the current step, and again disarms it`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.STEPS)
        assertEquals(ClipHint.Step(ClipField.STEPS, 3, null), vm.hint)
        vm.onSelectionChanged("Mix.")
        vm.onConfirm()
        // The next step still to do comes round from the start: Photo, then Name.
        assertEquals(ClipField.PHOTO, vm.uiState.value.armed)
        vm.onSkip()
        assertNull(vm.uiState.value.armed)
        vm.onFieldButton(ClipField.NAME)
        assertEquals(ClipHint.Step(ClipField.NAME, 1, null), vm.hint)
        vm.onSelectionChanged("Cookies")
        vm.onConfirm()
        assertEquals(ClipField.INGREDIENTS, vm.uiState.value.armed)

        vm.onFieldButton(ClipField.INGREDIENTS)
        assertNull(vm.uiState.value.armed)
        assertNull(vm.uiState.value.outline)
    }

    @Test fun `jumping to a skipped step takes it off the skipped`() {
        val vm = viewModel()
        vm.onSkip()
        vm.onFieldButton(ClipField.NAME)
        assertEquals(emptySet<ClipField>(), vm.uiState.value.skipped)
    }

    @Test fun `text selected first goes straight into the field tapped, with undo`() {
        val vm = viewModel()
        // A long press on the steps while Name is the step.
        vm.onSelectionChanged("Mix.\nBake.")
        assertEquals(ClipHint.Confirm(ClipField.NAME, 1), vm.hint)
        vm.onFieldButton(ClipField.STEPS)

        assertEquals(listOf("Mix.", "Bake."), vm.draft.steps)
        assertEquals("", vm.draft.name)
        assertEquals("m1", vm.uiState.value.newMarkId)
        assertEquals(emptyList<String>(), vm.uiState.value.selection)
        // Then on, as a confirm: the next step still to do (round from the start).
        assertEquals(ClipField.PHOTO, vm.uiState.value.armed)
        assertEquals(ClipHint.Step(ClipField.PHOTO, 4, ClipAdded(ClipField.STEPS, 2)), vm.hint)

        vm.onUndo()
        assertTrue(vm.draft.isEmpty)
        assertEquals(ClipField.STEPS, vm.uiState.value.armed)
    }

    @Test fun `select first appends ingredients and steps, and replaces a name`() {
        val vm = viewModel()
        vm.onSelectionChanged("flour")
        vm.onFieldButton(ClipField.INGREDIENTS)
        vm.onSelectionChanged("sugar\nbutter")
        vm.onFieldButton(ClipField.INGREDIENTS)
        assertEquals(listOf("flour", "sugar", "butter"), vm.draft.ingredients)

        vm.onSelectionChanged("Cookies")
        vm.onFieldButton(ClipField.NAME)
        vm.onSelectionChanged("Oat\nCookies")
        vm.onFieldButton(ClipField.NAME)
        assertEquals("Oat Cookies", vm.draft.name)
        assertEquals(listOf("m1", "m2", "m4"), vm.draft.marks.map { it.id })
    }

    @Test fun `select first on the armed field adds it too`() {
        val vm = viewModel()
        vm.onSelectionChanged("Cookies")
        vm.onFieldButton(ClipField.NAME)
        assertEquals("Cookies", vm.draft.name)
        assertEquals(ClipField.INGREDIENTS, vm.uiState.value.armed)
    }

    @Test fun `undo makes the undone add's field the current step again`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour", ClipField.INGREDIENTS)
        assertEquals(ClipField.STEPS, vm.uiState.value.armed)
        vm.onUndo()
        assertEquals(ClipField.INGREDIENTS, vm.uiState.value.armed)
        assertEquals(ClipHint.Step(ClipField.INGREDIENTS, 2, null), vm.hint)
    }

    @Test fun `a restored draft opens on its first empty step`() {
        viewModel().apply { select("Cookies", ClipField.NAME) }
        val reopened = viewModel(SavedStateHandle(mapOf(ClipViewModel.URL_ARG to cleaned)))
        assertEquals(ClipField.INGREDIENTS, reopened.uiState.value.armed)
    }

    @Test fun `discarding the draft starts the steps over`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.onSkip()
        vm.onDiscardDraft()
        assertEquals(ClipField.NAME, vm.uiState.value.armed)
        assertEquals(emptySet<ClipField>(), vm.uiState.value.skipped)
    }

    @Test fun `a selection while armed offers the confirm, and the confirm adds it`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.INGREDIENTS)
        vm.onSelectionChanged("1 cup flour\n2 eggs\n1 tsp salt")
        assertEquals(ClipHint.Confirm(ClipField.INGREDIENTS, 3), vm.hint)
        // Nothing is added before the confirm.
        assertTrue(vm.draft.isEmpty)

        vm.onConfirm()
        assertEquals(listOf("1 cup flour", "2 eggs", "1 tsp salt"), vm.draft.ingredients)
        assertEquals("m1", vm.uiState.value.newMarkId)
        assertEquals(emptyList<String>(), vm.uiState.value.selection)
        // The next step arms (Steps), and the header says what was added, with Undo.
        assertEquals(ClipField.STEPS, vm.uiState.value.armed)
        assertEquals(ClipHint.Step(ClipField.STEPS, 3, ClipAdded(ClipField.INGREDIENTS, 3)), vm.hint)
        // The words are in the hint bar, not a snackbar.
        assertNull(vm.message)
    }

    @Test fun `a name confirms as one line, replaces, and moves on`() {
        val vm = viewModel()
        vm.onSelectionChanged("Brown Butter\nOat Cookies")
        assertEquals(ClipHint.Confirm(ClipField.NAME, 1), vm.hint)
        vm.onConfirm()
        assertEquals("Brown Butter Oat Cookies", vm.draft.name)
        assertEquals(ClipField.INGREDIENTS, vm.uiState.value.armed)

        vm.select("Oat Cookies", ClipField.NAME)
        assertEquals("Oat Cookies", vm.draft.name)
        assertEquals(listOf("m2"), vm.draft.marks.map { it.id })
    }

    @Test fun `ingredients and steps add up across separate blocks`() {
        val vm = viewModel()
        vm.select("1 cup flour\n2 eggs", ClipField.INGREDIENTS)
        vm.select("For the glaze:\n1 cup sugar", ClipField.INGREDIENTS)
        vm.select("Mix.", ClipField.STEPS)
        vm.select("Bake.", ClipField.STEPS)

        assertEquals(listOf("1 cup flour", "2 eggs", "For the glaze:", "1 cup sugar"), vm.draft.ingredients)
        assertEquals(listOf("Mix.", "Bake."), vm.draft.steps)
        assertEquals(listOf("m1", "m2", "m3", "m4"), vm.draft.marks.map { it.id })
        assertEquals(ClipHint.Step(ClipField.PHOTO, 4, ClipAdded(ClipField.STEPS, 1)), vm.hint)
    }

    @Test fun `Clear drops the selection, keeps the step, and tells the page`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.STEPS)
        vm.onSelectionChanged("Mix.")
        val cleared = vm.uiState.value.clearSelection

        vm.onClearSelection()
        assertEquals(emptyList<String>(), vm.uiState.value.selection)
        assertEquals(ClipField.STEPS, vm.uiState.value.armed)
        assertEquals(cleared + 1, vm.uiState.value.clearSelection)
        vm.onConfirm()
        assertTrue(vm.draft.isEmpty)
    }

    @Test fun `text selected with nothing armed (a long press) asks for the field, which takes it`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.NAME) // disarmed
        vm.onSelectionChanged("Mix.\nBake.")
        assertEquals(ClipHint.Selected(2), vm.hint)
        vm.onConfirm()
        assertTrue(vm.draft.isEmpty)

        vm.onFieldButton(ClipField.STEPS)
        assertEquals(listOf("Mix.", "Bake."), vm.draft.steps)
    }

    @Test fun `confirming with nothing selected does nothing`() {
        val vm = viewModel()
        vm.onConfirm()
        vm.onFieldButton(ClipField.STEPS)
        vm.onConfirm()
        vm.onSelectionChanged(" \n ")
        vm.onConfirm()
        assertTrue(vm.draft.isEmpty)
        assertNull(vm.uiState.value.lastAdded)
    }

    @Test fun `the selection is used once`() {
        val vm = viewModel()
        vm.select("Mix.", ClipField.STEPS)
        vm.onFieldButton(ClipField.INGREDIENTS)
        vm.onConfirm()
        assertEquals(emptyList<String>(), vm.draft.ingredients)
    }

    @Test fun `the photo first, then the steps come round from Name, then the Done prompt`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.PHOTO)
        vm.onImageTapped("https://img.example/cookies.jpg")
        assertEquals(ClipHint.Step(ClipField.NAME, 1, ClipAdded(ClipField.PHOTO, 1)), vm.hint)

        vm.select("Cookies", ClipField.NAME)
        vm.select("flour\nsugar", ClipField.INGREDIENTS)
        vm.select("Bake.", ClipField.STEPS)
        assertEquals(ClipHint.Next(ClipAdded(ClipField.STEPS, 1), null), vm.hint)
        assertTrue(vm.uiState.value.guideDone)

        // Arming a field again moves on from what was added.
        vm.onFieldButton(ClipField.NAME)
        assertEquals(ClipHint.Step(ClipField.NAME, 1, null), vm.hint)
    }

    @Test fun `disarmed with steps still to do, the hint names the next one`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.onFieldButton(ClipField.INGREDIENTS)
        assertEquals(ClipHint.Next(null, ClipField.INGREDIENTS), vm.hint)
    }

    // #264: Undo is offered only for the change just made, so it keeps only that one.
    @Test fun `undo takes back the last add only`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour\nsugar", ClipField.INGREDIENTS)
        vm.select("butter", ClipField.INGREDIENTS)

        vm.onUndo()
        assertEquals(listOf("flour", "sugar"), vm.draft.ingredients)
        assertEquals(listOf("m1", "m2"), vm.draft.marks.map { it.id })
        assertNull(vm.uiState.value.newMarkId)
        assertNull(vm.uiState.value.lastAdded)

        vm.onUndo()
        assertEquals(listOf("flour", "sugar"), vm.draft.ingredients)
    }

    // #264: the page keeps the marks it recorded, so an undone add's id is never handed out again.
    @Test fun `after an undo the next add takes a new mark id`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour", ClipField.INGREDIENTS)
        assertEquals("m2", vm.uiState.value.newMarkId)
        vm.onUndo()
        vm.select("sugar", ClipField.INGREDIENTS)
        assertEquals("m3", vm.uiState.value.newMarkId)
        assertEquals(listOf("m1", "m3"), vm.draft.marks.map { it.id })
    }

    @Test fun `after a discard the next add takes a new mark id`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.onDiscardDraft()
        vm.select("Pie", ClipField.NAME)
        assertEquals("m2", vm.uiState.value.newMarkId)
    }

    // #264: its Undo would take back the newest change, so the snackbar goes at the next one.
    @Test fun `a removal's snackbar goes once another change is made`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("Mix.\nBake.", ClipField.STEPS)
        vm.onTagTapped("m2")
        assertEquals(ClipMessage.Removed(ClipField.STEPS, 2), vm.message)

        vm.select("Cool.", ClipField.STEPS)
        assertNull(vm.message)
        vm.onUndo()
        assertEquals(emptyList<String>(), vm.draft.steps)
        assertEquals("Cookies", vm.draft.name)
    }

    @Test fun `the hint bar's undo of a removal takes its snackbar too`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.onTagTapped("m1")
        vm.onUndo()
        assertEquals("Cookies", vm.draft.name)
        assertNull(vm.message)
    }

    // #264: a hand edit ends Undo, so the hint bar no longer offers one.
    @Test fun `an edit in review takes the last add's undo away`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour", ClipField.INGREDIENTS)
        vm.onReview()
        vm.onLineChange(ClipField.INGREDIENTS, 0, "plain flour")
        vm.onBackToPage()
        assertNull(vm.uiState.value.lastAdded)
        assertTrue(vm.hint.let { it is ClipHint.Step && it.added == null || it is ClipHint.Next && it.added == null })
        vm.onUndo()
        assertEquals(listOf("plain flour"), vm.draft.ingredients)
    }

    @Test fun `tapping an add's tag takes back just that add, with undo`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("Mix.\nBake.", ClipField.STEPS)
        vm.select("Cool.", ClipField.STEPS)

        vm.onTagTapped("m2")
        assertEquals(listOf("Cool."), vm.draft.steps)
        assertEquals("Cookies", vm.draft.name)
        assertEquals(ClipMessage.Removed(ClipField.STEPS, 2), vm.message)

        vm.onUndo()
        assertEquals(listOf("Mix.", "Bake.", "Cool."), vm.draft.steps)
    }

    @Test fun `a tag the draft no longer holds does nothing`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.onTagTapped("m9")
        assertEquals("Cookies", vm.draft.name)
        assertNull(vm.message)
    }

    @Test fun `the photo is the next image tapped after the Photo button, and only that`() {
        val vm = viewModel()
        vm.onImageTapped("https://img.example/ad.jpg")
        assertNull(vm.draft.photo)

        vm.onFieldButton(ClipField.PHOTO)
        assertTrue(vm.uiState.value.pickingPhoto)
        assertEquals(ClipHint.Step(ClipField.PHOTO, 4, null), vm.hint)
        vm.onImageTapped("https://img.example/cookies.jpg")
        vm.onImageTapped("https://img.example/ad.jpg")

        assertEquals("https://img.example/cookies.jpg", vm.draft.photo)
        assertFalse(vm.uiState.value.pickingPhoto)
        assertEquals(ClipAdded(ClipField.PHOTO, 1), vm.uiState.value.lastAdded)
    }

    // #235: the page says which address was tapped; only a web image becomes the photo.

    @Test fun `a photo address that isn't a web image is no picture, said as one`() {
        listOf(
            "file:///data/data/com.example.recipeclipper/databases/recipe_clipper.db",
            "content://media/external/images/media/12",
            "data:image/gif;base64,R0lGODlhAQABAAAAACw=",
            "javascript:alert(1)",
            "/img/cookies.jpg",
            ""
        ).forEach { src ->
            val vm = viewModel()
            vm.onFieldButton(ClipField.PHOTO)
            vm.onImageTapped(src)
            assertNull(src, vm.draft.photo)
            assertFalse(src, vm.uiState.value.pickingPhoto)
            assertEquals(src, ClipMessage.PhotoUnreadable, vm.message)
        }
    }

    @Test fun `an http photo is kept as https`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.PHOTO)
        vm.onImageTapped("http://img.example/cookies.jpg")
        assertEquals("https://img.example/cookies.jpg", vm.draft.photo)
    }

    @Test fun `the Photo button disarms photo picking again`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.PHOTO)
        vm.onFieldButton(ClipField.PHOTO)
        assertFalse(vm.uiState.value.pickingPhoto)
    }

    @Test fun `arming the photo drops a text selection, which it can't use`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.STEPS)
        vm.onSelectionChanged("Mix.")
        vm.onFieldButton(ClipField.PHOTO)
        assertEquals(emptyList<String>(), vm.uiState.value.selection)
        assertEquals(ClipHint.Step(ClipField.PHOTO, 4, null), vm.hint)
    }

    // The owner's "stuck in the photo section": a tap on a picture the page can't give an
    // address for left picking on, every later tap swallowed and the other fields waiting.

    @Test fun `a tap with no readable picture ends picking, says so, and the other fields go on`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.PHOTO)
        vm.onNoImageTapped()

        assertFalse(vm.uiState.value.pickingPhoto)
        assertNull(vm.draft.photo)
        assertEquals(ClipMessage.PhotoUnreadable, vm.message)

        vm.select("Brown Butter Oat Cookies", ClipField.NAME)
        assertEquals("Brown Butter Oat Cookies", vm.draft.name)
    }

    @Test fun `a tap with no readable picture keeps the photo there was`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.PHOTO)
        vm.onImageTapped("https://img.example/cookies.jpg")
        vm.onFieldButton(ClipField.PHOTO)
        vm.onNoImageTapped()
        assertEquals("https://img.example/cookies.jpg", vm.draft.photo)
        assertFalse(vm.uiState.value.pickingPhoto)
    }

    @Test fun `a no-image tap when not picking says nothing`() {
        val vm = viewModel()
        vm.onNoImageTapped()
        assertNull(vm.uiState.value.notice)
    }

    @Test fun `Skip leaves the photo step without a photo`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.PHOTO)
        vm.onSkip()
        assertFalse(vm.uiState.value.pickingPhoto)
        assertNull(vm.draft.photo)
        assertNull(vm.uiState.value.notice)
    }

    @Test fun `selecting text (a long press) while picking moves on from the photo`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.PHOTO)
        vm.onSelectionChanged("1 cup flour\n2 eggs")
        assertFalse(vm.uiState.value.pickingPhoto)

        vm.onFieldButton(ClipField.INGREDIENTS)
        vm.onConfirm()
        assertEquals(listOf("1 cup flour", "2 eggs"), vm.draft.ingredients)
        assertFalse(vm.uiState.value.pickingPhoto)
    }

    @Test fun `a selection cleared while picking leaves picking on`() {
        val vm = viewModel()
        vm.onSelectionChanged("Brown Butter")
        vm.onFieldButton(ClipField.PHOTO)
        // Arming the photo dropped the selection; the page reports it gone.
        vm.onSelectionChanged("")
        assertTrue(vm.uiState.value.pickingPhoto)
    }

    @Test fun `tapping a tag while picking ends picking`() {
        val vm = viewModel()
        vm.select("Brown Butter", ClipField.NAME)
        vm.onFieldButton(ClipField.PHOTO)
        vm.onTagTapped("m1")
        assertFalse(vm.uiState.value.pickingPhoto)
        assertEquals(ClipMessage.Removed(ClipField.NAME, 1), vm.message)
    }

    @Test fun `done opens review once there is a name plus ingredients or steps`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("Bake.", ClipField.STEPS)
        vm.onReview()
        assertTrue(vm.uiState.value.reviewing)

        vm.onBackToPage()
        assertFalse(vm.uiState.value.reviewing)
    }

    // The owner's S23 (#213): ingredients and steps selected on a Reddit post, no name (its title
    // is hard to select there), and Done did nothing, greyed out, with nothing saying why.
    @Test fun `done with lines but no name opens review, saying to type the name`() {
        val vm = viewModel()
        vm.select("flour\nsugar", ClipField.INGREDIENTS)
        vm.select("Mix.\nBake.", ClipField.STEPS)
        vm.onReview()
        assertTrue(vm.uiState.value.reviewing)
        assertEquals(ClipMessage.Missing(name = true, lines = false), vm.message)
    }

    @Test fun `done with a name but no lines stays on the page, saying what to select`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.onReview()
        assertFalse(vm.uiState.value.reviewing)
        assertEquals(ClipMessage.Missing(name = false, lines = true), vm.message)
    }

    @Test fun `done with nothing yet says everything is missing`() {
        val vm = viewModel()
        vm.onReview()
        assertFalse(vm.uiState.value.reviewing)
        assertEquals(ClipMessage.Missing(name = true, lines = true), vm.message)
    }

    @Test fun `review edits lines and takes typed serves and time`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour\nsugar", ClipField.INGREDIENTS)
        vm.onReview()

        vm.onLineChange(ClipField.INGREDIENTS, 1, "brown sugar")
        vm.onLineAdd(ClipField.INGREDIENTS)
        vm.onLineChange(ClipField.INGREDIENTS, 2, "2 eggs")
        vm.onLineRemove(ClipField.INGREDIENTS, 0)
        vm.onNameChange("Oat Cookies")
        vm.onServesChange("24 cookies")
        vm.onTotalTimeChange("45 min")

        assertEquals(listOf("brown sugar", "2 eggs"), vm.draft.ingredients)
        assertEquals("Oat Cookies", vm.draft.name)
        assertEquals("24 cookies", vm.draft.serves)
        assertEquals("45 min", vm.draft.totalTime)
        assertTrue(vm.uiState.value.reviewing)
    }

    @Test fun `saving writes the recipe, drops the draft and opens it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour", ClipField.INGREDIENTS)
        vm.onFieldButton(ClipField.PHOTO)
        vm.onImageTapped("https://img.example/c.jpg")
        vm.onReview()
        vm.onServesChange("24")

        vm.onSave()
        advanceUntilIdle()

        val saved = repository.saveClipCalls.single()
        assertEquals("Cookies", saved.name)
        assertEquals(listOf("flour"), saved.ingredients)
        assertEquals("https://img.example/c.jpg", saved.image)
        assertEquals("24", saved.yield)
        assertEquals(cleaned, saved.sourceUrl)
        assertEquals(1L, vm.uiState.value.savedRecipeId)
        assertNull(store.get(cleaned))
    }

    @Test fun `a failed save keeps the draft and says so`() = runTest(mainDispatcherRule.dispatcher) {
        repository.saveClipResult = ParseResult.Error(ParseError.SaveFailed)
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour", ClipField.INGREDIENTS)

        vm.onSave()
        advanceUntilIdle()

        assertEquals(ClipMessage.SaveFailed, vm.message)
        assertNull(vm.uiState.value.savedRecipeId)
        assertFalse(vm.uiState.value.saving)
        assertEquals("Cookies", store.get(cleaned)?.name)
    }

    @Test fun `saving without a name saves nothing and says to type it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.select("flour", ClipField.INGREDIENTS)
        vm.onReview()
        vm.onNameChange("  ")
        vm.onSave()
        advanceUntilIdle()
        assertTrue(repository.saveClipCalls.isEmpty())
        assertEquals(ClipMessage.Missing(name = true, lines = false), vm.message)
        assertFalse(vm.uiState.value.saving)

        // Typed in Review, the name is enough.
        vm.onNameChange("Cookies")
        vm.onSave()
        advanceUntilIdle()
        assertEquals("Cookies", repository.saveClipCalls.single().name)
        assertEquals(1L, vm.uiState.value.savedRecipeId)
    }

    @Test fun `saving with the lines blanked in review says ingredients or steps are missing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            vm.select("Cookies", ClipField.NAME)
            vm.select("flour", ClipField.INGREDIENTS)
            vm.onReview()
            vm.onLineChange(ClipField.INGREDIENTS, 0, " ")
            vm.onSave()
            advanceUntilIdle()
            assertTrue(repository.saveClipCalls.isEmpty())
            assertEquals(ClipMessage.Missing(name = false, lines = true), vm.message)
        }

    @Test fun `a full library says so rather than saving nothing silently`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour", ClipField.INGREDIENTS)
        repository.saveClipResult = ParseResult.Success(vm.draft.toRecipe()!!, kept = false)
        vm.onSave()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.libraryFull)
        assertNull(vm.uiState.value.savedRecipeId)
        assertFalse(vm.uiState.value.saving)
    }

    @Test fun `a reddit share link saves under the link that was shared, not the page it loads from`() =
        runTest(mainDispatcherRule.dispatcher) {
            val shareLink = "https://www.reddit.com/r/recipes/s/AbCdEf123"
            val vm = viewModel(SavedStateHandle(mapOf(ClipViewModel.URL_ARG to shareLink, ClipViewModel.BLOCKED_ARG to true)))
            vm.select("Soup", ClipField.NAME)
            vm.select("water", ClipField.INGREDIENTS)
            vm.onSave()
            advanceUntilIdle()
            assertEquals(shareLink, repository.saveClipCalls.single().sourceUrl)
            assertEquals(1L, vm.uiState.value.savedRecipeId)
        }

    // --- The Text view (#213) ---

    private val post = "https://www.reddit.com/r/recipes/comments/1abc01/apple_pie/"
    private val postHtml = """
        <shreddit-post post-title="Apple Pie"><div slot="text-body"><div property="schema:articleBody">
          <ul><li><p>3 apples</p></li><li><p>1 crust</p></li></ul><p>Bake.</p>
        </div></div></shreddit-post>
        <shreddit-comment author="baker" depth="0"><div slot="comment"><p>Use 4 apples.</p></div></shreddit-comment>
    """.trimIndent()

    private fun redditViewModel() = viewModel(SavedStateHandle(mapOf(ClipViewModel.URL_ARG to post, ClipViewModel.BLOCKED_ARG to true)))

    @Test fun `only a reddit post offers the text view`() {
        val blog = viewModel()
        assertFalse(blog.uiState.value.offersText)
        blog.onShowText()
        assertFalse(blog.uiState.value.readingText)
        assertTrue(redditViewModel().uiState.value.offersText)
    }

    @Test fun `the text view reads the page, then shows the post as text`() {
        val vm = redditViewModel()
        vm.onSelectionChanged("something on the page")
        vm.onShowText()
        assertTrue(vm.uiState.value.readingText)
        assertEquals(emptyList<String>(), vm.uiState.value.selection)

        vm.onPageText(postHtml)
        val state = vm.uiState.value
        assertFalse(state.readingText)
        assertTrue(state.showingText)
        assertEquals("Apple Pie", state.pageText?.title)
        assertEquals(listOf("3 apples", "1 crust", "Bake."), state.pageText?.body)
        assertEquals(listOf("Use 4 apples."), state.pageText?.comments?.single()?.lines)
    }

    // The Text view, line by line (#286): the post's rows, the title first, labels without a box.
    @Test fun `the text view is the post's lines as rows`() {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        assertEquals(
            listOf(
                ClipTextRow.Line(0, "Apple Pie", title = true),
                ClipTextRow.Line(1, "3 apples"),
                ClipTextRow.Line(2, "1 crust"),
                ClipTextRow.Line(3, "Bake."),
                ClipTextRow.Comments,
                ClipTextRow.Author(0, "baker", 0),
                ClipTextRow.Line(4, "Use 4 apples.", depth = 0)
            ),
            vm.uiState.value.textRows
        )
        // Keys are unique across the list (a LazyColumn's).
        val keys = vm.uiState.value.textRows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `lines ticked in the text view are assigned and saved like the page's`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)

        // Guided: step 1 is armed, a tick, then the step's confirm.
        vm.onTextLine(0)
        assertEquals(setOf(0), vm.uiState.value.ticked)
        assertEquals(ClipHint.Confirm(ClipField.NAME, 1), vm.hint)
        vm.onConfirm()
        assertEquals(emptySet<Int>(), vm.uiState.value.ticked)
        assertEquals(ClipField.INGREDIENTS, vm.uiState.value.armed)
        vm.onTextLine(1)
        vm.onTextLine(2)
        assertEquals(ClipHint.Confirm(ClipField.INGREDIENTS, 2), vm.hint)
        vm.onConfirm()
        vm.onTextLine(3)
        vm.onConfirm()
        vm.onReview()
        vm.onSave()
        advanceUntilIdle()

        val saved = repository.saveClipCalls.single()
        assertEquals("Apple Pie", saved.name)
        assertEquals(listOf("3 apples", "1 crust"), saved.ingredients)
        assertEquals(listOf("Bake."), saved.instructions)
        assertEquals(post, saved.sourceUrl)
    }

    @Test fun `a tap past the ticked lines ticks the run, and a ticked line unticks`() {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        vm.onFieldButton(ClipField.INGREDIENTS)

        vm.onTextLine(1)
        vm.onTextLine(4)
        assertEquals(setOf(1, 2, 3, 4), vm.uiState.value.ticked)
        assertEquals(listOf("3 apples", "1 crust", "Bake.", "Use 4 apples."), vm.uiState.value.selection)
        vm.onTextLine(3)
        assertEquals(setOf(1, 2, 4), vm.uiState.value.ticked)
        // A tap in a gap ticks that line only; one before the run stretches it back.
        vm.onTextLine(3)
        assertEquals(setOf(1, 2, 3, 4), vm.uiState.value.ticked)
        vm.onTextLine(0)
        assertEquals(setOf(0, 1, 2, 3, 4), vm.uiState.value.ticked)

        // A name is a line or two, never a run: each tap ticks its own line.
        vm.onClearSelection()
        vm.onFieldButton(ClipField.NAME)
        vm.onTextLine(0)
        vm.onTextLine(2)
        assertEquals(setOf(0, 2), vm.uiState.value.ticked)
        assertEquals(listOf("Apple Pie", "1 crust"), vm.uiState.value.selection)
    }

    @Test fun `lines ticked first go in with one tap on a field's button`() {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        vm.onFieldButton(ClipField.NAME) // disarms step 1
        vm.onTextLine(1)
        vm.onTextLine(2)
        assertEquals(ClipHint.Selected(2), vm.hint)

        vm.onFieldButton(ClipField.INGREDIENTS)
        assertEquals(listOf("3 apples", "1 crust"), vm.draft.ingredients)
        assertEquals(emptySet<Int>(), vm.uiState.value.ticked)
        assertEquals(ClipField.STEPS, vm.uiState.value.armed)
    }

    @Test fun `an add keeps its lines marked and its first line tagged, until it is taken back`() {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        vm.onFieldButton(ClipField.INGREDIENTS)
        vm.onTextLine(1)
        vm.onTextLine(2)
        vm.onConfirm()
        val mark = vm.draft.marks.single()
        assertEquals(mapOf(1 to ClipTextMark(mark, first = true), 2 to ClipTextMark(mark, first = false)), vm.uiState.value.textMarks)

        // The tag takes the add back, and Undo brings it back, marks and all.
        vm.onTagTapped(mark.id)
        assertEquals(emptyList<String>(), vm.draft.ingredients)
        assertEquals(emptyMap<Int, ClipTextMark>(), vm.uiState.value.textMarks)
        vm.onUndo()
        assertEquals(setOf(1, 2), vm.uiState.value.textMarks.keys)
    }

    @Test fun `ticks go with Clear and the other view, and the page's selection isn't heard over them`() {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        vm.onTextLine(0)
        // The page under the Text view only reports dropping its own selection.
        vm.onSelectionChanged("")
        assertEquals(listOf("Apple Pie"), vm.uiState.value.selection)
        vm.onClearSelection()
        assertEquals(emptySet<Int>(), vm.uiState.value.ticked)
        assertEquals(emptyList<String>(), vm.uiState.value.selection)

        vm.onTextLine(0)
        vm.onShowPage()
        assertEquals(emptySet<Int>(), vm.uiState.value.ticked)
        vm.onTextLine(1) // the Text view isn't showing
        assertEquals(emptySet<Int>(), vm.uiState.value.ticked)
    }

    @Test fun `back to the page keeps the text and the draft`() {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        vm.onTextLine(0)
        vm.onConfirm()
        vm.onShowPage()
        assertFalse(vm.uiState.value.showingText)
        assertEquals("Apple Pie", vm.uiState.value.pageText?.title)
        assertEquals("Apple Pie", vm.draft.name)

        // Reading the same text again keeps its rows and the name's mark on them.
        val before = vm.uiState.value.textRows
        vm.onShowText()
        vm.onPageText(postHtml)
        assertTrue(before === vm.uiState.value.textRows)
        assertEquals(setOf(0), vm.uiState.value.textMarks.keys)

        // New text (more comments loaded) is new rows: the old marks can't be found on them.
        vm.onShowPage()
        vm.onShowText()
        vm.onPageText(postHtml.replace("Use 4 apples.", "Use 5 apples."))
        assertEquals(emptyMap<Int, ClipTextMark>(), vm.uiState.value.textMarks)
        assertEquals("Use 5 apples.", (vm.uiState.value.textRows.last() as ClipTextRow.Line).text)
    }

    // The owner (#237): "when toggling a reddit page from text back to page, page doesnt work".
    // Page, Text, Page: the page takes the armed field's selections again, as before.
    @Test fun `page, then text, then page again, the armed field carries over and the page works as before`() {
        val vm = redditViewModel()
        vm.onFieldButton(ClipField.INGREDIENTS)
        vm.onSelectionChanged("on the page")
        val cleared = vm.uiState.value.clearSelection

        vm.onShowText()
        vm.onPageText(postHtml)
        assertEquals(ClipField.INGREDIENTS, vm.uiState.value.armed)
        assertEquals(cleared + 1, vm.uiState.value.clearSelection)
        vm.onTextLine(1)
        vm.onTextLine(2)
        vm.onConfirm()
        // The next step (Steps) armed in the Text view, and carries back to the page.
        assertEquals(ClipField.STEPS, vm.uiState.value.armed)

        vm.onShowPage()
        val state = vm.uiState.value
        assertFalse(state.showingText)
        assertFalse(state.readingText)
        assertEquals(ClipField.STEPS, state.armed)
        assertEquals(emptyList<String>(), state.selection)
        assertNull(state.newMarkId)
        assertEquals(cleared + 2, state.clearSelection)

        // The page's next selection goes in as the Text view's did (select first, #251).
        vm.onSelectionChanged("1 tsp cinnamon")
        assertEquals(ClipHint.Confirm(ClipField.STEPS, 1), vm.hint)
        vm.onFieldButton(ClipField.INGREDIENTS)
        assertEquals(listOf("3 apples", "1 crust", "1 tsp cinnamon"), vm.draft.ingredients)
        assertEquals("m2", vm.uiState.value.newMarkId)
    }

    @Test fun `a page with no post yet says so and stays on the page`() {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText("<html><body><p>Checking your browser</p></body></html>")
        assertFalse(vm.uiState.value.showingText)
        assertFalse(vm.uiState.value.readingText)
        assertEquals(ClipMessage.TextUnreadable, vm.message)
    }

    @Test fun `picking the photo goes back to the page`() {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        vm.onFieldButton(ClipField.PHOTO)
        assertTrue(vm.uiState.value.pickingPhoto)
        assertFalse(vm.uiState.value.showingText)
    }

    @Test fun `the text view never keeps photo picking`() {
        val vm = redditViewModel()
        vm.onFieldButton(ClipField.PHOTO)
        vm.onShowText()
        assertFalse(vm.uiState.value.pickingPhoto)
    }

    @Test fun `leaving keeps the draft for the session, and reopening restores it`() {
        viewModel().apply {
            select("Cookies", ClipField.NAME)
            select("flour", ClipField.INGREDIENTS)
        }

        val reopened = viewModel(SavedStateHandle(mapOf(ClipViewModel.URL_ARG to cleaned)))
        assertEquals("Cookies", reopened.draft.name)
        assertEquals(listOf("flour"), reopened.draft.ingredients)
        assertEquals(ClipMessage.DraftRestored, reopened.message)
    }

    @Test fun `a draft belongs to its page`() {
        viewModel().select("Cookies", ClipField.NAME)
        val other = viewModel(SavedStateHandle(mapOf(ClipViewModel.URL_ARG to "https://other.example/pie")))
        assertTrue(other.draft.isEmpty)
        assertNull(other.uiState.value.notice)
    }

    @Test fun `discard starts over and forgets the draft`() {
        viewModel().select("Cookies", ClipField.NAME)
        val reopened = viewModel()
        reopened.onDiscardDraft()
        assertTrue(reopened.draft.isEmpty)
        assertNull(store.get(cleaned))
        assertTrue(viewModel().draft.isEmpty)
    }

    @Test fun `clearing everything leaves no draft behind`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.onTagTapped("m1")
        assertNull(store.get(cleaned))
    }

    @Test fun `the draft survives the process through saved state, without its page marks`() {
        val handle = SavedStateHandle(mapOf(ClipViewModel.URL_ARG to shared))
        viewModel(handle).apply {
            select("Cookies", ClipField.NAME)
            select("Mix.", ClipField.STEPS)
        }

        val afterDeath = ClipViewModel(handle, repository, ClipDraftStore())
        assertEquals(ClipDraft(cleaned, name = "Cookies", steps = listOf("Mix.")), afterDeath.draft)
        assertEquals(ClipMessage.DraftRestored, afterDeath.message)
    }

    // #264: the page finds the photo again by its address, so its tag must name a mark.
    @Test fun `a photo restored after the process died has a mark its tag takes back`() {
        val handle = SavedStateHandle(mapOf(ClipViewModel.URL_ARG to shared))
        viewModel(handle).apply {
            select("Cookies", ClipField.NAME)
            onFieldButton(ClipField.PHOTO)
            onImageTapped("https://img.example/c.jpg")
        }

        val afterDeath = ClipViewModel(handle, repository, ClipDraftStore())
        val mark = afterDeath.draft.marks.single()
        assertEquals(ClipField.PHOTO, mark.field)
        assertTrue(mark.id.isNotEmpty())
        afterDeath.onTagTapped(mark.id)
        assertNull(afterDeath.draft.photo)
        assertEquals("Cookies", afterDeath.draft.name)
    }

    @Test fun `a shown notice is cleared only by its own serial`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("Mix.", ClipField.STEPS)
        vm.onTagTapped("m1")
        val first = vm.uiState.value.notice!!
        vm.onTagTapped("m2")
        vm.onNoticeShown(first.serial)
        assertEquals(ClipMessage.Removed(ClipField.STEPS, 1), vm.message)
        vm.onNoticeShown(vm.uiState.value.notice!!.serial)
        assertNull(vm.uiState.value.notice)
    }
}
