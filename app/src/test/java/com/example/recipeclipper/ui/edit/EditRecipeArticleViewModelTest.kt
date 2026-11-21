package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.ClipDraftStore
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
import com.example.recipeclipper.ui.article.ArticleChoiceRow
import com.example.recipeclipper.ui.article.ArticleChoiceViewModel
import com.example.recipeclipper.ui.clip.ClipCheck
import com.example.recipeclipper.ui.clip.ClipViewModel
import com.example.recipeclipper.ui.recipe.RecipeContent
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Recipes written inside an article (#435): the import hands them on in its own place (the editor
 * on one, the chooser over several), the chooser lists them and which are saved, and the editor
 * opens filled in from one, saved only on Save, as a clip under its own link. Past Cloudflare's
 * check (#220) the clip view hands them on the same way. iOS: `EditRecipeArticleTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditRecipeArticleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val url = "https://recetas.example.com/articulo-mejillones.html"

    private val article = ArticleRecipe(
        name = "Bolas de parmesano",
        image = "https://cdn.example.com/mejillones.jpg",
        ingredients = listOf("1 lata de mejillones en escabeche.", "100-120 gramos de queso parmesano rallado.", "1 huevo."),
        steps = listOf("Rallamos el parmesano y lo mezclamos con el huevo.", "Freímos las bolas durante dos minutos."),
        language = "es",
        sourceUrl = url,
        headline = "Los cocineros coinciden: los mejillones de lata"
    )
    private val second = article.copy(name = "Crema de tomate", steps = listOf("Trituramos los tomates."), sourceUrl = "$url?pith_recipe=2")

    private fun TestScope.importing(vararg found: ArticleRecipe): Pair<RecipeViewModel, FakeRecipeRepository> {
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(ParseError.InArticle(found.toList())) }
        val vm = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to url)), repository, FakeAppPreferences(),
            Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler()
        )
        advanceUntilIdle()
        return vm to repository
    }

    private fun editor(repository: FakeRecipeRepository, index: Int = 0) = EditRecipeViewModel(
        SavedStateHandle(mapOf(EditRecipeViewModel.ARTICLE_URL_ARG to url, EditRecipeViewModel.ARTICLE_INDEX_ARG to index)),
        repository
    )

    @Test fun `one found is handed on in the import's place, with no error screen`() = runTest(mainDispatcherRule.dispatcher) {
        val (vm, repository) = importing(article)
        val state = vm.uiState.value
        assertEquals(listOf(article), state.articleRecipes)
        assertEquals(RecipeContent.Loading, state.content)
        assertNull(state.clipUrl)
        assertNull(state.reportSiteUrl)
        assertTrue(repository.saveClipCalls.isEmpty())
    }

    @Test fun `several found are all handed on, for the chooser`() = runTest(mainDispatcherRule.dispatcher) {
        val (vm, _) = importing(article, second)
        assertEquals(listOf(article, second), vm.uiState.value.articleRecipes)
        assertEquals(RecipeContent.Loading, vm.uiState.value.content)
    }

    @Test fun `the editor opens filled in from the page, as written`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { articleRecipesResult = listOf(article) }
        val vm = editor(repository)
        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(url), repository.articleRecipesCalls)
        assertFalse(state.loading)
        assertTrue(state.article)
        assertEquals(true, state.articleFound)
        assertEquals(article.name, state.draft.name)
        assertEquals(article.image, state.draft.image)
        assertEquals(article.ingredients.joinToString("\n"), state.draft.ingredientsText)
        assertEquals(article.steps.joinToString("\n"), state.draft.instructionsText)
        assertTrue(repository.saveClipCalls.isEmpty())
    }

    @Test fun `Save keeps the checked recipe as a clip under the page's link, in its language`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { articleRecipesResult = listOf(article) }
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
            assertEquals("es", saved.language)
            assertEquals(1L, vm.uiState.value.savedId)
            assertTrue(repository.addManualCalls.isEmpty())
        }

    @Test fun `the chooser's second recipe is edited and saved under its own link`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { articleRecipesResult = listOf(article, second) }
        val vm = editor(repository, index = 1)
        advanceUntilIdle()
        assertEquals("Crema de tomate", vm.uiState.value.draft.name)
        vm.onSave()
        advanceUntilIdle()
        assertEquals("$url?pith_recipe=2", repository.saveClipCalls.single().sourceUrl)
    }

    @Test fun `Back without saving saves nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { articleRecipesResult = listOf(article) }
        editor(repository)
        advanceUntilIdle()
        assertTrue(repository.saveClipCalls.isEmpty())
        assertTrue(repository.addManualCalls.isEmpty())
    }

    @Test fun `a page that no longer holds it opens the fields empty, saying so`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = editor(FakeRecipeRepository())
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.articleFound)
        assertEquals("", vm.uiState.value.draft.ingredientsText)
    }

    @Test fun `the chooser lists each with its counts, and a saved one once it is saved`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { articleRecipesResult = listOf(article, second) }
        val vm = ArticleChoiceViewModel(SavedStateHandle(mapOf(ArticleChoiceViewModel.URL_ARG to url)), repository)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(article.headline, state.headline)
        assertEquals(article.image, state.image)
        assertEquals(listOf(ArticleChoiceRow(0, "Bolas de parmesano", 3, 2), ArticleChoiceRow(1, "Crema de tomate", 3, 1)), state.rows)

        // Back from the editor, which saved the second.
        repository.savedLinks["$url?pith_recipe=2"] = 9L
        vm.onShown()
        advanceUntilIdle()
        assertEquals(listOf(null, 9L), vm.uiState.value.rows.map { it.savedId })
    }

    @Test fun `a chooser whose page no longer holds them is empty`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ArticleChoiceViewModel(SavedStateHandle(mapOf(ArticleChoiceViewModel.URL_ARG to url)), FakeRecipeRepository())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.rows.isEmpty())
    }

    private fun clip(repository: FakeRecipeRepository) = ClipViewModel(
        SavedStateHandle(mapOf(ClipViewModel.URL_ARG to url, ClipViewModel.CHECK_ARG to true)), repository, ClipDraftStore()
    )

    @Test fun `past the check, one found is handed on in the clip's place`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            importPageResults["<html>page</html>"] = ParseResult.Error(ParseError.InArticle(listOf(article)))
        }
        val vm = clip(repository)
        vm.onPageLoaded("<html>page</html>")
        advanceUntilIdle()
        assertEquals(listOf(article), vm.uiState.value.articleRecipes)
        // Nothing is read again while it is handed on.
        vm.onPageLoaded("<html>other</html>")
        advanceUntilIdle()
        assertEquals(1, repository.importPageCalls.size)
    }

    @Test fun `past the check, several found are handed on for the chooser`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            importPageResults["<html>page</html>"] = ParseResult.Error(ParseError.InArticle(listOf(article, second)))
        }
        val vm = clip(repository)
        vm.onPageLoaded("<html>page</html>")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.articleRecipes?.size)
    }

    @Test fun `past the check, none found offers the clip as before`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            importPageResults["<html>page</html>"] = ParseResult.Error(ParseError.NoRecipeFound)
        }
        val vm = clip(repository)
        vm.onPageLoaded("<html>page</html>")
        advanceUntilIdle()
        assertNull(vm.uiState.value.articleRecipes)
        assertEquals(ClipCheck.NO_RECIPE, vm.uiState.value.check)
    }
}
