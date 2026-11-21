package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The recipe screen's step photos (#433): what the content carries, and the viewer's state. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeStepPhotosTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val recipe = Recipe(
        name = "Torta di mele", image = null, ingredients = listOf("3 mele"),
        instructions = listOf("Mescola (1).", "Sbuccia (3) (4).", "Accendi il forno.", "Cuoci (7)."),
        prepTime = null, cookTime = null, totalTime = null, yield = null,
        sourceUrl = "https://www.cookist.it/torta", id = 1L,
        stepPhotos = listOf(listOf("https://img.example/1.jpg"), listOf("https://img.example/3.jpg", "https://img.example/4.jpg"), emptyList(), listOf("https://img.example/7.jpg"))
    )

    private fun TestScope.viewModel(recipe: Recipe): RecipeViewModel = RecipeViewModel(
        SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to 1L)),
        FakeRecipeRepository().apply { openResult = recipe },
        FakeAppPreferences(), Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler()
    )

    private val RecipeViewModel.content get() = uiState.value.content as RecipeContent.Success

    @Test
    fun `the content numbers each step's photos by its markers, and lists them in order for the viewer`() = runTest {
        val vm = viewModel(recipe)
        advanceUntilIdle()
        assertEquals(listOf(listOf(1), listOf(3, 4), emptyList(), listOf(7)), vm.content.stepPhotos.map { s -> s.map { it.number } })
        assertEquals(listOf(0, 1, 1, 3), vm.content.allStepPhotos.map { it.step })
        assertEquals(emptyList<Any>(), vm.content.photosOf(2))
        // The text stays as written, markers and all.
        assertEquals("Sbuccia (3) (4).", vm.content.instructions[1])
    }

    @Test
    fun `the viewer opens on a photo, follows the pages, and closes`() = runTest {
        val vm = viewModel(recipe)
        advanceUntilIdle()
        assertNull(vm.uiState.value.stepPhotoViewer)

        vm.onStepPhotoOpen(1)
        assertEquals(1, vm.uiState.value.stepPhotoViewer)
        vm.onStepPhotoPage(3)
        assertEquals(3, vm.uiState.value.stepPhotoViewer)
        // A page that isn't there is ignored.
        vm.onStepPhotoPage(4)
        assertEquals(3, vm.uiState.value.stepPhotoViewer)
        vm.onStepPhotoClose()
        assertNull(vm.uiState.value.stepPhotoViewer)
    }

    @Test
    fun `a recipe with no step photos has none, and the viewer never opens`() = runTest {
        val vm = viewModel(recipe.copy(stepPhotos = emptyList()))
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), vm.content.stepPhotos)
        vm.onStepPhotoOpen(0)
        assertNull(vm.uiState.value.stepPhotoViewer)
    }
}
