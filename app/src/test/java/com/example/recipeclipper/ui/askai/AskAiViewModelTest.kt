package com.example.recipeclipper.ui.askai

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.AskAiPrompt
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskAiViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = 1_800_000_000_000L
    private val today = PlanDays.today(now)

    private var pantry = FakePantryRepository()
    private val lists = FakeListRepository()
    private val recipes = FakeRecipeRepository()
    private val preferences = FakeAppPreferences()
    private val words = AskAiPrompt.Words.ENGLISH

    private fun viewModel() = AskAiViewModel(pantry, lists, recipes, preferences, Clock { now })

    private fun item(id: Long, name: String, expires: Long? = null, inStock: Boolean = true) = PantryItem(
        id = id, name = name, quantity = null, language = "en", aisle = Aisle.OTHER, inStock = inStock,
        alwaysHave = false, purchasedDay = null, expiresDay = expires
    )

    private fun summary(id: Long, title: String, cooked: Long? = null) = RecipeSummary(
        id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = id, isSaved = true, lastCookedDay = cooked
    )

    private fun TestScope.start(): AskAiViewModel {
        val vm = viewModel()
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        return vm
    }

    @Test fun `the cook's own words need words`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = start()
        assertFalse(vm.uiState.value.canSend)
        assertNull(vm.prompt(words))

        vm.onRequestChange("  ")
        assertFalse(vm.uiState.value.canSend)

        vm.onRequestChange("a lentil soup")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canSend)
        assertTrue(vm.prompt(words)!!.startsWith("I want to cook: a lentil soup"))
    }

    @Test fun `the prompt follows the units and temperature chosen in Settings`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = start()
        vm.onRequestChange("soup")
        advanceUntilIdle()
        assertTrue(vm.prompt(words)!!.contains("Use the units that suit the recipe."))

        preferences.unitSystem = UnitSystem.METRIC
        preferences.temperatureUnit = TemperatureUnit.CELSIUS
        advanceUntilIdle()
        val prompt = vm.prompt(words)!!
        assertTrue(prompt.contains("metric units"))
        assertTrue(prompt.contains("°C"))
    }

    @Test fun `what can I make carries the pantry, the items to use up first`() = runTest(mainDispatcherRule.dispatcher) {
        pantry = FakePantryRepository(listOf(item(1, "rice"), item(2, "spinach", expires = today + 1), item(3, "flour", inStock = false)))
        val vm = start()
        assertEquals(2, vm.uiState.value.pantryCount)
        assertEquals(1, vm.uiState.value.useUpCount)

        vm.onKind(AskAiKind.WHAT_CAN_I_MAKE)
        vm.onRequestChange("quick")
        advanceUntilIdle()
        val prompt = vm.prompt(words)!!
        assertTrue(prompt.contains("Use these up first, they expire soon: spinach."))
        assertTrue(prompt.contains("Also in my pantry: rice."))
        assertTrue(prompt.contains("Also: quick"))
    }

    @Test fun `what can I make is off while the pantry is empty`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = start()
        assertFalse(vm.uiState.value.canAskWhatCanIMake)
        vm.onKind(AskAiKind.WHAT_CAN_I_MAKE)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canSend)
        assertNull(vm.prompt(words))
    }

    @Test fun `plan my week draws on the favourites and what was cooked lately, for seven days`() =
        runTest(mainDispatcherRule.dispatcher) {
            lists.lists.value = listOf(
                RecipeList(1, "Lunch", isBuiltIn = true, isFavorites = false, recipeCount = 0),
                RecipeList(2, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0)
            )
            lists.recipesIn.value = listOf(summary(1, "Lasagne"), summary(2, "Pad Thai"))
            recipes.history.value = listOf(
                summary(1, "Lasagne"),
                summary(3, "Chili", cooked = today - 1),
                summary(4, "Stew", cooked = today - 5),
                summary(5, "Never cooked")
            )
            val vm = start()
            assertEquals(2, vm.uiState.value.favouriteCount)
            assertEquals(2, vm.uiState.value.cookedCount)

            vm.onKind(AskAiKind.PLAN_MY_WEEK)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canSend)
            val prompt = vm.prompt(words)!!
            assertTrue(prompt.contains("- ${AskAiPrompt.isoDate(today)} ("))
            assertTrue(prompt.contains("- ${AskAiPrompt.isoDate(today + 6)} ("))
            assertFalse(prompt.contains(AskAiPrompt.isoDate(today + 7)))
            assertTrue(prompt.contains("Recipes I like, for ideas: Lasagne, Pad Thai."))
            // The latest cooked first.
            assertTrue(prompt.contains("so please don't repeat them: Chili, Stew."))
        }

    @Test fun `change this recipe needs a request and carries the recipe`() = runTest(mainDispatcherRule.dispatcher) {
        val recipe = Recipe(
            name = "Adobo", image = null, ingredients = listOf("2 lb chicken"), instructions = listOf("Simmer."),
            prepTime = null, cookTime = null, totalTime = null, yield = "4 servings", sourceUrl = "manual:abc"
        )
        val vm = start()
        assertFalse(vm.uiState.value.canSendChange)
        assertNull(vm.changePrompt(recipe, words))

        vm.onRequestChange("make it vegetarian")
        advanceUntilIdle()
        val prompt = vm.changePrompt(recipe, words)
        assertNotNull(prompt)
        assertTrue(prompt!!.startsWith("Please change this recipe: make it vegetarian"))
        assertTrue(prompt.contains("\"isBasedOn\": \"manual:abc\""))
    }

    @Test fun `copied lasts until something changes, and closing the sheet starts over`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = start()
            vm.onRequestChange("soup")
            vm.onCopied()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.copied)

            vm.onRequestChange("soup!")
            advanceUntilIdle()
            assertFalse(vm.uiState.value.copied)

            vm.onKind(AskAiKind.PLAN_MY_WEEK)
            vm.onReset()
            advanceUntilIdle()
            assertEquals("", vm.uiState.value.request)
            assertEquals(AskAiKind.DESCRIBE, vm.uiState.value.kind)
        }
}
