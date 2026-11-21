package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.ArticleRecipe
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import com.example.recipeclipper.ui.recipe.RecipeContent
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A recipe written inside an article (#435): the import's screen offers it, and the editor opens
 * filled in from it, saved only on Save, as a clip under the page's link. iOS: `EditRecipeArticleTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditRecipeArticleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val url = "https://recetas.example.com/articulo-mejillones.html"

    private val article = ArticleRecipe(
        name = "Los cocineros coinciden: los mejillones de lata",
        image = "https://cdn.example.com/mejillones.jpg",
        ingredients = listOf("1 lata de mejillones en escabeche.", "100-120 gramos de queso parmesano rallado.", "1 huevo."),
        steps = listOf("Rallamos el parmesano y lo mezclamos con el huevo.", "Freímos las bolas durante dos minutos."),
        language = "es",
        sourceUrl = url
    )

    private fun editor(repository: FakeRecipeRepository) =
        EditRecipeViewModel(SavedStateHandle(mapOf(EditRecipeViewModel.ARTICLE_URL_ARG to url)), repository)

    @Test fun `the import's screen offers the article's recipe, with nothing else`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(ParseError.InArticle(article)) }
        val vm = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to url)), repository, FakeAppPreferences(),
            Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler()
        )
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(RecipeContent.Error(ParseError.InArticle(article)), state.content)
        // Clip it yourself and Report this site are for pages where nothing was found.
        assertNull(state.clipUrl)
        assertNull(state.reportSiteUrl)
        assertTrue(repository.saveClipCalls.isEmpty())
    }

    @Test fun `the editor opens filled in from the page, as written`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { articleRecipeResult = article }
        val vm = editor(repository)
        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(url), repository.articleRecipeCalls)
        assertFalse(state.loading)
        assertTrue(state.article)
        assertEquals(true, state.articleFound)
        assertEquals(article.name, state.draft.name)
        assertEquals(article.image, state.draft.image)
        assertEquals(article.ingredients.joinToString("\n"), state.draft.ingredientsText)
        assertEquals(article.steps.joinToString("\n"), state.draft.instructionsText)
        // Nothing is saved until Save.
        assertTrue(repository.saveClipCalls.isEmpty())
    }

    @Test fun `Save keeps the checked recipe as a clip under the page's link, in its language`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { articleRecipeResult = article }
            val vm = editor(repository)
            advanceUntilIdle()
            vm.onDraftChange(vm.uiState.value.draft.copy(name = "Bolas de parmesano con mejillones"))
            vm.onSave()
            advanceUntilIdle()

            val saved: Recipe = repository.saveClipCalls.single()
            assertEquals("Bolas de parmesano con mejillones", saved.name)
            assertEquals(url, saved.sourceUrl)
            assertEquals(article.ingredients, saved.ingredients)
            assertEquals(article.steps, saved.instructions)
            assertEquals(article.image, saved.image)
            assertEquals("es", saved.language)
            assertEquals(1L, vm.uiState.value.savedId)
            assertTrue(repository.addManualCalls.isEmpty())
        }

    @Test fun `a page that no longer holds it opens the fields empty, saying so`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = editor(FakeRecipeRepository())
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.articleFound)
        assertEquals("", vm.uiState.value.draft.ingredientsText)
    }

    @Test fun `a full library is not left, as a clip's`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            articleRecipeResult = article
            saveClipResult = ParseResult.Success(
                Recipe(
                    name = article.name, image = null, ingredients = article.ingredients, instructions = article.steps,
                    prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = url
                ),
                kept = false
            )
        }
        val vm = editor(repository)
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.libraryFull)
        assertNull(vm.uiState.value.savedId)
    }
}
