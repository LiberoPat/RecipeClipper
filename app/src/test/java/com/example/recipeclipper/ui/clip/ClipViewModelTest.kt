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

    @Test fun `the Photo button toggles picking off again`() {
        val vm = viewModel()
        vm.onPhotoButton()
        vm.onPhotoButton()
        assertFalse(vm.uiState.value.pickingPhoto)
    }

    @Test fun `review opens only once there is a name plus ingredients or steps`() {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.onReview()
        assertFalse(vm.uiState.value.reviewing)

        vm.select("Bake.", ClipField.STEPS)
        vm.onReview()
        assertTrue(vm.uiState.value.reviewing)

        vm.onBackToPage()
        assertFalse(vm.uiState.value.reviewing)
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

    @Test fun `saving does nothing until it can finish`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.select("Cookies", ClipField.NAME)
        vm.onSave()
        advanceUntilIdle()
        assertTrue(repository.saveClipCalls.isEmpty())
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
