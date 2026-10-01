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

    private fun ClipViewModel.select(text: String, field: ClipField) {
        onSelectionChanged(text)
        onAssign(field)
    }

    private val ClipViewModel.draft get() = uiState.value.draft
    private val ClipViewModel.message get() = uiState.value.notice?.message

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

    @Test fun `assigning replaces the field, marks it and says how many`() {
        val vm = viewModel()
        vm.select("a\nb\nc\nd\ne\nf\ng\nh", ClipField.INGREDIENTS)
        vm.select("1\n2\n3\n4", ClipField.INGREDIENTS)

        assertEquals(listOf("1", "2", "3", "4"), vm.draft.ingredients)
        assertEquals(ClipMessage.Assigned(ClipField.INGREDIENTS, 4), vm.message)
        assertEquals("m2", vm.uiState.value.newMarkId)
        assertEquals(emptyList<String>(), vm.uiState.value.selection)
    }

    @Test fun `a name joins the selected lines`() {
        val vm = viewModel()
        vm.select("Brown Butter\nOat Cookies", ClipField.NAME)
        assertEquals("Brown Butter Oat Cookies", vm.draft.name)
    }

    @Test fun `assigning with nothing selected does nothing`() {
        val vm = viewModel()
        vm.onAssign(ClipField.STEPS)
        vm.select(" \n ", ClipField.STEPS)
        assertTrue(vm.draft.isEmpty)
        assertNull(vm.uiState.value.notice)
    }

    @Test fun `the selection is used once`() {
        val vm = viewModel()
        vm.select("Mix.", ClipField.STEPS)
        vm.onAssign(ClipField.INGREDIENTS)
        assertEquals(emptyList<String>(), vm.draft.ingredients)
    }

    @Test fun `undo puts back the draft before the last assignment, once`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("flour\nsugar", ClipField.INGREDIENTS)
        vm.select("butter", ClipField.INGREDIENTS)

        vm.onUndo()
        assertEquals(listOf("flour", "sugar"), vm.draft.ingredients)
        assertEquals("m2", vm.draft.marks[ClipField.INGREDIENTS])
        assertNull(vm.uiState.value.newMarkId)

        vm.onUndo()
        assertEquals(listOf("flour", "sugar"), vm.draft.ingredients)
    }

    @Test fun `tapping a tag clears that field, with undo`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.select("Mix.\nBake.", ClipField.STEPS)

        vm.onTagTapped(ClipField.STEPS)
        assertEquals(emptyList<String>(), vm.draft.steps)
        assertEquals("Cookies", vm.draft.name)
        assertEquals(ClipMessage.Cleared(ClipField.STEPS), vm.message)

        vm.onUndo()
        assertEquals(listOf("Mix.", "Bake."), vm.draft.steps)
    }

    @Test fun `the photo is the next image tapped after the Photo button, and only that`() {
        val vm = viewModel()
        vm.onImageTapped("https://img.example/ad.jpg")
        assertNull(vm.draft.photo)

        vm.onPhotoButton()
        assertTrue(vm.uiState.value.pickingPhoto)
        vm.onImageTapped("https://img.example/cookies.jpg")
        vm.onImageTapped("https://img.example/ad.jpg")

        assertEquals("https://img.example/cookies.jpg", vm.draft.photo)
        assertFalse(vm.uiState.value.pickingPhoto)
        assertEquals(ClipMessage.Assigned(ClipField.PHOTO, 1), vm.message)
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
            vm.onPhotoButton()
            vm.onImageTapped(src)
            assertNull(src, vm.draft.photo)
            assertFalse(src, vm.uiState.value.pickingPhoto)
            assertEquals(src, ClipMessage.PhotoUnreadable, vm.message)
        }
    }

    @Test fun `an http photo is kept as https`() {
        val vm = viewModel()
        vm.onPhotoButton()
        vm.onImageTapped("http://img.example/cookies.jpg")
        assertEquals("https://img.example/cookies.jpg", vm.draft.photo)
    }

    @Test fun `the Photo button toggles picking off again`() {
        val vm = viewModel()
        vm.onPhotoButton()
        vm.onPhotoButton()
        assertFalse(vm.uiState.value.pickingPhoto)
    }

    // The owner's "stuck in the photo section": a tap on a picture the page can't give an
    // address for left picking on, every later tap swallowed and the other fields waiting.

    @Test fun `a tap with no readable picture ends picking, says so, and the other fields go on`() {
        val vm = viewModel()
        vm.onPhotoButton()
        vm.onNoImageTapped()

        assertFalse(vm.uiState.value.pickingPhoto)
        assertNull(vm.draft.photo)
        assertEquals(ClipMessage.PhotoUnreadable, vm.message)

        vm.select("Brown Butter Oat Cookies", ClipField.NAME)
        assertEquals("Brown Butter Oat Cookies", vm.draft.name)
        assertEquals(ClipMessage.Assigned(ClipField.NAME, 1), vm.message)
    }

    @Test fun `a tap with no readable picture keeps the photo there was`() {
        val vm = viewModel()
        vm.onPhotoButton()
        vm.onImageTapped("https://img.example/cookies.jpg")
        vm.onPhotoButton()
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
        vm.onPhotoButton()
        vm.onSkipPhoto()
        assertFalse(vm.uiState.value.pickingPhoto)
        assertNull(vm.draft.photo)
        assertNull(vm.uiState.value.notice)
    }

    @Test fun `selecting text while picking moves on from the photo`() {
        val vm = viewModel()
        vm.onPhotoButton()
        vm.onSelectionChanged("1 cup flour\n2 eggs")
        assertFalse(vm.uiState.value.pickingPhoto)

        vm.onAssign(ClipField.INGREDIENTS)
        assertEquals(listOf("1 cup flour", "2 eggs"), vm.draft.ingredients)
        // Nothing sends the toolbar back to "Tap the picture" once the selection is used.
        assertFalse(vm.uiState.value.pickingPhoto)
    }

    @Test fun `the same selection reported again, or cleared, leaves picking on`() {
        val vm = viewModel()
        vm.onSelectionChanged("Brown Butter")
        vm.onPhotoButton()
        vm.onSelectionChanged("Brown Butter")
        assertTrue(vm.uiState.value.pickingPhoto)
        vm.onSelectionChanged("")
        assertTrue(vm.uiState.value.pickingPhoto)
    }

    @Test fun `tapping a tag while picking ends picking`() {
        val vm = viewModel()
        vm.select("Brown Butter", ClipField.NAME)
        vm.onPhotoButton()
        vm.onTagTapped(ClipField.NAME)
        assertFalse(vm.uiState.value.pickingPhoto)
        assertEquals(ClipMessage.Cleared(ClipField.NAME), vm.message)
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
        vm.onPhotoButton()
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
        vm.onPhotoButton()
        assertTrue(vm.uiState.value.pickingPhoto)
        assertFalse(vm.uiState.value.showingText)
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
        vm.onTagTapped(ClipField.NAME)
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
        val first = vm.uiState.value.notice!!
        vm.select("Mix.", ClipField.STEPS)
        vm.onNoticeShown(first.serial)
        assertEquals(ClipMessage.Assigned(ClipField.STEPS, 1), vm.message)
        vm.onNoticeShown(vm.uiState.value.notice!!.serial)
        assertNull(vm.uiState.value.notice)
    }
}
