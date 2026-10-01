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

    @Test fun `it opens with nothing armed, pointing to Name`() {
        val vm = viewModel()
        assertNull(vm.uiState.value.armed)
        assertEquals(ClipHint.Next(added = null, next = ClipField.NAME), vm.hint)
    }

    @Test fun `a field button arms its field, again disarms it, and another switches`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.INGREDIENTS)
        assertEquals(ClipField.INGREDIENTS, vm.uiState.value.armed)
        assertEquals(ClipHint.Select(ClipField.INGREDIENTS), vm.hint)

        vm.onFieldButton(ClipField.STEPS)
        assertEquals(ClipField.STEPS, vm.uiState.value.armed)

        vm.onFieldButton(ClipField.STEPS)
        assertNull(vm.uiState.value.armed)
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
        // Ingredients stay armed for the next block; the hint says what was added and what's next.
        assertEquals(ClipField.INGREDIENTS, vm.uiState.value.armed)
        assertEquals(ClipHint.Next(ClipAdded(ClipField.INGREDIENTS, 3), ClipField.NAME), vm.hint)
        // The words are in the hint bar, not a snackbar.
        assertNull(vm.message)
    }

    @Test fun `a name confirms as one line, replaces, and disarms`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.NAME)
        vm.onSelectionChanged("Brown Butter\nOat Cookies")
        assertEquals(ClipHint.Confirm(ClipField.NAME, 1), vm.hint)
        vm.onConfirm()
        assertEquals("Brown Butter Oat Cookies", vm.draft.name)
        assertNull(vm.uiState.value.armed)

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
        assertEquals(ClipHint.Next(ClipAdded(ClipField.STEPS, 1), ClipField.NAME), vm.hint)
    }

    @Test fun `switching fields keeps the selection for the new one`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.STEPS)
        vm.onSelectionChanged("1 cup flour\n2 eggs")
        vm.onFieldButton(ClipField.INGREDIENTS)
        assertEquals(ClipHint.Confirm(ClipField.INGREDIENTS, 2), vm.hint)
        vm.onConfirm()
        assertEquals(listOf("1 cup flour", "2 eggs"), vm.draft.ingredients)
        assertEquals(emptyList<String>(), vm.draft.steps)
    }

    @Test fun `disarming, or Clear, drops the selection and tells the page`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.STEPS)
        vm.onSelectionChanged("Mix.")
        val cleared = vm.uiState.value.clearSelection

        vm.onClearSelection()
        assertEquals(emptyList<String>(), vm.uiState.value.selection)
        assertEquals(ClipField.STEPS, vm.uiState.value.armed)
        assertEquals(cleared + 1, vm.uiState.value.clearSelection)

        vm.onSelectionChanged("Bake.")
        vm.onFieldButton(ClipField.STEPS)
        assertNull(vm.uiState.value.armed)
        assertEquals(emptyList<String>(), vm.uiState.value.selection)
        assertEquals(cleared + 2, vm.uiState.value.clearSelection)
        vm.onConfirm()
        assertTrue(vm.draft.isEmpty)
    }

    @Test fun `text selected with nothing armed (a long press) asks for the field, which then confirms`() {
        val vm = viewModel()
        vm.onSelectionChanged("Mix.\nBake.")
        assertEquals(ClipHint.Selected(2), vm.hint)
        vm.onConfirm()
        assertTrue(vm.draft.isEmpty)

        vm.onFieldButton(ClipField.STEPS)
        assertEquals(ClipHint.Confirm(ClipField.STEPS, 2), vm.hint)
        vm.onConfirm()
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

    @Test fun `the hint walks through the fields, then says to tap Done`() {
        val vm = viewModel()
        vm.onFieldButton(ClipField.PHOTO)
        vm.onImageTapped("https://img.example/cookies.jpg")
        // The owner's example: "Photo added. Next: tap Name".
        assertEquals(ClipHint.Next(ClipAdded(ClipField.PHOTO, 1), ClipField.NAME), vm.hint)

        vm.select("Cookies", ClipField.NAME)
        assertEquals(ClipHint.Next(ClipAdded(ClipField.NAME, 1), ClipField.INGREDIENTS), vm.hint)
        vm.select("flour\nsugar", ClipField.INGREDIENTS)
        assertEquals(ClipHint.Next(ClipAdded(ClipField.INGREDIENTS, 2), ClipField.STEPS), vm.hint)
        vm.select("Bake.", ClipField.STEPS)
        assertEquals(ClipHint.Next(ClipAdded(ClipField.STEPS, 1), null), vm.hint)

        // Arming another field moves on from what was added.
        vm.onFieldButton(ClipField.NAME)
        assertEquals(ClipHint.Select(ClipField.NAME), vm.hint)
    }

    @Test fun `without a photo, the hint points to it last`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour", ClipField.INGREDIENTS)
        vm.select("Bake.", ClipField.STEPS)
        assertEquals(ClipHint.Next(ClipAdded(ClipField.STEPS, 1), ClipField.PHOTO), vm.hint)
    }

    @Test fun `undo takes back each add in turn`() {
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
        assertEquals(emptyList<String>(), vm.draft.ingredients)
        vm.onUndo()
        assertTrue(vm.draft.isEmpty)
        vm.onUndo()
        assertTrue(vm.draft.isEmpty)
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
        assertEquals(ClipHint.PickPhoto, vm.hint)
        vm.onImageTapped("https://img.example/cookies.jpg")
        vm.onImageTapped("https://img.example/ad.jpg")

        assertEquals("https://img.example/cookies.jpg", vm.draft.photo)
        assertFalse(vm.uiState.value.pickingPhoto)
        assertEquals(ClipAdded(ClipField.PHOTO, 1), vm.uiState.value.lastAdded)
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
        assertEquals(ClipHint.PickPhoto, vm.hint)
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
        vm.onSkipPhoto()
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

    @Test fun `text selected in the text view is assigned and saved like the page's`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)

        vm.select("Apple Pie", ClipField.NAME)
        assertEquals("m1", vm.uiState.value.newMarkId)
        vm.select("3 apples\n1 crust", ClipField.INGREDIENTS)
        vm.select("Bake.", ClipField.STEPS)
        vm.onReview()
        vm.onSave()
        advanceUntilIdle()

        val saved = repository.saveClipCalls.single()
        assertEquals("Apple Pie", saved.name)
        assertEquals(listOf("3 apples", "1 crust"), saved.ingredients)
        assertEquals(listOf("Bake."), saved.instructions)
        assertEquals(post, saved.sourceUrl)
    }

    @Test fun `back to the page keeps the text and the draft`() {
        val vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        vm.select("Apple Pie", ClipField.NAME)
        vm.onShowPage()
        assertFalse(vm.uiState.value.showingText)
        assertEquals("Apple Pie", vm.uiState.value.pageText?.title)
        assertEquals("Apple Pie", vm.draft.name)

        // Reading the same text again keeps the same value, so the view keeps its marks.
        val before = vm.uiState.value.pageText
        vm.onShowText()
        vm.onPageText(postHtml)
        assertTrue(before === vm.uiState.value.pageText)
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
        vm.onSelectionChanged("3 apples\n1 crust")
        vm.onConfirm()

        vm.onShowPage()
        val state = vm.uiState.value
        assertFalse(state.showingText)
        assertFalse(state.readingText)
        assertEquals(ClipField.INGREDIENTS, state.armed)
        assertEquals(emptyList<String>(), state.selection)
        assertNull(state.newMarkId)
        assertEquals(cleared + 2, state.clearSelection)

        // The page's next selection goes in as the Text view's did.
        vm.onSelectionChanged("1 tsp cinnamon")
        assertEquals(ClipHint.Confirm(ClipField.INGREDIENTS, 1), vm.hint)
        vm.onConfirm()
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
