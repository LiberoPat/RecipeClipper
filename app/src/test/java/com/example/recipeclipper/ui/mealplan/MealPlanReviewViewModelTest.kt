package com.example.recipeclipper.ui.mealplan

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.MealPlanImport
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.edit.SharedTextInbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A week of recipes shared in (#352): the review, then Save into the library and Week. iOS: `MealPlanReviewTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealPlanReviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val calendar = FakePlanCalendar()
    private val today = calendar.today
    private val inbox = SharedTextInbox()
    private val recipes = FakeRecipeRepository()
    private val week = FakeMealPlanRepository()
    private val policy = FakeLibraryPolicy(LibraryLimit.Unlimited)
    private val entitlements = FakeEntitlements()

    private fun recipe(name: String, id: Long = 0) = Recipe(
        id = id, name = name, image = null, ingredients = listOf("1 egg", "2 cups rice"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, yield = "2 servings", sourceUrl = "", language = "en"
    )

    private fun plan(vararg days: Pair<String, Long?>) =
        MealPlanImport.Plan(days.map { (name, day) -> MealPlanImport.Item(recipe(name), day) })

    private fun viewModel(plan: MealPlanImport.Plan): MealPlanReviewViewModel {
        val key = inbox.offerPlan("the shared text", plan)
        return MealPlanReviewViewModel(
            SavedStateHandle(mapOf(MealPlanReviewViewModel.PLAN_ARG to key)),
            recipes, week, calendar, policy, entitlements, inbox, mainDispatcherRule.dispatcher
        )
    }

    private fun savedAs(vararg ids: Long) = ids.forEach { recipes.addManualQueue.add(recipe("saved", it)) }

    private val dinner get() = MealType.defaultTypeId(week.types.value)

    @Test fun `every recipe is ticked with its day, and nothing is saved before Save`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(plan("Omelette" to today, "Soup" to today + 3, "Stew" to today + 40))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.loading)
            assertEquals(listOf("Omelette", "Soup", "Stew"), state.items.map { it.name })
            assertEquals(listOf(today, today + 3, today + 40), state.items.map { it.day })
            assertTrue(state.items.all { it.ticked && it.fits && !it.passed })
            assertEquals("1 egg, 2 cups rice", state.items[0].preview)
            assertTrue(recipes.addManualCalls.isEmpty())
            assertTrue(week.meals.value.isEmpty())
        }

    @Test fun `a day that has passed, or none, leaves the recipe unplanned until the cook picks one`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(plan("Old" to today - 2, "Undated" to null, "Fine" to today))
            advanceUntilIdle()

            val items = vm.uiState.value.items
            assertNull(items[0].day)
            assertTrue(items[0].passed)
            assertNull(items[1].day)
            assertFalse(items[1].passed)
            assertEquals(today, items[2].day)

            savedAs(1, 2, 3)
            vm.onDay(0, today + 1)
            vm.onSave()
            advanceUntilIdle()
            assertEquals(listOf(today + 1, today), week.meals.value.map { it.day })
        }

    @Test fun `Save saves each kept recipe as from a chat and plans it on its day under Dinner`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(plan("Omelette" to today, "Soup" to today + 1, "Stew" to today + 2))
            advanceUntilIdle()
            savedAs(11, 12)
            vm.onTick(1) // Soup is left out
            vm.onDay(2, today + 5)
            vm.onSave()
            advanceUntilIdle()

            assertEquals(listOf("Omelette", "Stew"), recipes.addManualCalls.map { it.name })
            assertEquals(listOf(true, true), recipes.addManualFromChat)
            assertEquals(listOf("en", "en"), recipes.addManualLanguages)
            assertEquals(listOf(11L to today, 12L to today + 5), week.meals.value.map { it.recipeId!! to it.day })
            assertTrue(week.meals.value.all { it.mealTypeId == dinner })
            val state = vm.uiState.value
            assertEquals(2, state.kept)
            assertEquals(2, state.planned)
            assertTrue(state.finished)
        }

    @Test fun `a recipe with no day is saved but not planned, and nothing ticked saves nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(plan("A" to today, "B" to null))
            advanceUntilIdle()
            savedAs(1, 2)
            vm.onDay(0, null)
            vm.onSave()
            advanceUntilIdle()
            assertEquals(2, recipes.addManualCalls.size)
            assertTrue(week.meals.value.isEmpty())
            assertEquals(2, vm.uiState.value.kept)
            assertEquals(0, vm.uiState.value.planned)

            val none = viewModel(plan("A" to today, "B" to today))
            advanceUntilIdle()
            none.onTick(0)
            none.onTick(1)
            none.onSave()
            advanceUntilIdle()
            assertEquals(2, recipes.addManualCalls.size)
        }

    @Test fun `the free library says how many fit, saves those and never removes anything`() =
        runTest(mainDispatcherRule.dispatcher) {
            policy.limit = LibraryLimit.Capped(50)
            recipes.count.value = 48
            val vm = viewModel(plan("A" to today, "B" to today + 1, "C" to today + 2))
            advanceUntilIdle()

            var state = vm.uiState.value
            assertEquals(2, state.room)
            assertEquals(listOf(true, true, false), state.items.map { it.fits })
            assertTrue(state.overLimit)
            assertEquals(2, state.savingCount)

            // Leaving the first out lets the third in: the first that fit, in order.
            vm.onTick(0)
            assertEquals(listOf(true, true, true), vm.uiState.value.items.map { it.fits })
            vm.onTick(0)

            savedAs(1, 2)
            vm.onSave()
            advanceUntilIdle()
            assertEquals(listOf("A", "B"), recipes.addManualCalls.map { it.name })
            assertTrue(recipes.deleteCalls.isEmpty())
            state = vm.uiState.value
            assertEquals(listOf("C"), state.items.map { it.name })
            assertEquals(2, state.kept)
            assertFalse(state.finished)
            assertEquals(2, week.meals.value.size)
        }

    @Test fun `unlocking makes room for the rest, which Save then keeps`() =
        runTest(mainDispatcherRule.dispatcher) {
            policy.limit = LibraryLimit.Capped(50)
            recipes.count.value = 49
            val vm = viewModel(plan("A" to today, "B" to today + 1))
            advanceUntilIdle()
            assertEquals(listOf(true, false), vm.uiState.value.items.map { it.fits })

            savedAs(1)
            vm.onSave()
            advanceUntilIdle()
            assertEquals(listOf("B"), vm.uiState.value.items.map { it.name })
            recipes.count.value = 50
            advanceUntilIdle()
            assertEquals(0, vm.uiState.value.room)
            assertEquals(0, vm.uiState.value.savingCount)

            vm.onUnlock()
            policy.limit = LibraryLimit.Unlimited
            advanceUntilIdle()
            assertEquals(1, entitlements.purchases)
            assertNull(vm.uiState.value.room)
            assertTrue(vm.uiState.value.items.single().fits)

            savedAs(2)
            vm.onSave()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.finished)
            assertEquals(2, vm.uiState.value.kept)
        }

    @Test fun `a recipe the one upsert doesn't keep stays in the review`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(plan("A" to today, "B" to today + 1))
            advanceUntilIdle()
            recipes.addManualQueue.add(recipe("A", 0)) // not kept: id 0
            recipes.addManualQueue.add(recipe("B", 5))
            vm.onSave()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf("A"), state.items.map { it.name })
            assertEquals(1, state.kept)
            assertEquals(listOf(5L), week.meals.value.map { it.recipeId })
            assertFalse(state.finished)
        }

    @Test fun `a failed save keeps the recipe listed and says so`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(plan("A" to today, "B" to today))
            advanceUntilIdle()
            recipes.addManualQueue.add(null)
            recipes.addManualQueue.add(recipe("B", 9))
            vm.onSave()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.saveFailed)
            assertEquals(listOf("A"), state.items.map { it.name })
            assertFalse(state.finished)
        }

    @Test fun `after process death the text is read again, every recipe ticked`() =
        runTest(mainDispatcherRule.dispatcher) {
            val text = """[{"@type": "Recipe", "name": "Omelette", "day": "2026-09-24", "recipeIngredient": ["1 egg"], "recipeInstructions": ["Cook."]},
                {"@type": "Recipe", "name": "Soup", "day": "2026-09-25", "recipeIngredient": ["1 leek"], "recipeInstructions": ["Simmer."]}]"""
            val vm = MealPlanReviewViewModel(
                SavedStateHandle(mapOf(MealPlanReviewViewModel.PLAN_STATE to text)),
                recipes, week, calendar, policy, entitlements, inbox, mainDispatcherRule.dispatcher
            )
            advanceUntilIdle()
            assertEquals(listOf("Omelette", "Soup"), vm.uiState.value.items.map { it.name })
            assertEquals(listOf(today + 1, today + 2), vm.uiState.value.items.map { it.day })
            assertTrue(vm.uiState.value.items.all { it.ticked })
        }
}
