package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import java.util.concurrent.atomic.AtomicLong

/**
 * The recipe screen as the Compose tests see it: a real [RecipeViewModel] and
 * [SaveToListViewModel] over the shared fakes, opened by id, with a [Clock] the test moves by
 * hand. The ViewModel's timer ticks every 250 ms of real time but reads the time from [now],
 * so a 20-minute timer finishes as soon as a test moves [now] on, not 20 minutes later.
 */
class RecipeScreenFixture(recipe: Recipe = testRecipe()) {

    val recipes = FakeRecipeRepository().apply { openResult = recipe }
    val lists = FakeListRepository().apply {
        lists.value = listOf(
            RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0),
            RecipeList(2, "Dinner", isBuiltIn = true, isFavorites = false, recipeCount = 0)
        )
    }
    val preferences = FakeAppPreferences()

    /** Wall-clock milliseconds as the ViewModel sees them. Written by the test thread, read on main. */
    val now = AtomicLong(1_000_000L)

    var backs = 0
        private set

    lateinit var viewModel: RecipeViewModel
        private set

    fun show(compose: ComposeContentTestRule) {
        viewModel = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to RECIPE_ID)),
            recipes,
            preferences,
            Clock { now.get() },
            FakeConnectivity()
        )
        val saveViewModel = SaveToListViewModel(lists)
        compose.setContent {
            RecipeScreen(onBack = { backs++ }, viewModel = viewModel, saveViewModel = saveViewModel)
        }
        compose.waitForIdle()
    }

    fun advanceClockBy(millis: Long) {
        now.addAndGet(millis)
    }

    companion object {
        const val RECIPE_ID = 1L

        fun testRecipe(
            instructions: List<String> = listOf(
                "Preheat the oven to 350°F.",
                "Simmer for 20 minutes.",
                "Stir in the milk.",
                "Serve."
            )
        ) = Recipe(
            name = "Test Stew",
            image = null,
            ingredients = listOf("2 cups flour", "1 cup milk"),
            instructions = instructions,
            prepTime = "10m",
            cookTime = "20m",
            totalTime = "30m",
            yield = "4 servings",
            sourceUrl = "https://example.com/stew",
            id = RECIPE_ID
        )
    }
}

/**
 * Whether this text is drawn struck through — how cook mode marks a done step. Read from the
 * text's own layout, since strikethrough isn't a semantics property. Use on an unmerged node.
 */
fun SemanticsNodeInteraction.isStruckThrough(): Boolean {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
    val style = results.firstOrNull()?.layoutInput?.style
        ?: throw AssertionError("No text layout on this node; is it a Text?")
    return style.textDecoration?.contains(TextDecoration.LineThrough) == true
}
