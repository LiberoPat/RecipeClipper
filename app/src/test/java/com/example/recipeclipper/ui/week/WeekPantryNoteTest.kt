package com.example.recipeclipper.ui.week

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.PantryGap
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.LUNCH
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Plan's quiet pantry note (#456, `planPantryNote`) through [WeekViewModel]. iOS: `WeekPantryNoteTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class WeekPantryNoteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = FakePlanCalendar.WEDNESDAY
    private val plan = FakeMealPlanRepository()
    private val pantry = FakePantryRepository()

    private fun item(id: Long, name: String, inStock: Boolean = true) =
        PantryItem(id, name, null, "en", Aisle.OTHER, inStock, alwaysHave = false, purchasedDay = null, expiresDay = null)

    private fun flags(on: Boolean) =
        FeatureFlags(FakeFeatureFlagStore(mapOf("planPantryNote" to on)), FlagRegistry.definitions, isDebug = true)

    private val groceries = FakeGroceryRepository()

    private fun viewModel(on: Boolean = true, decisions: FakeDecisionRepository? = null) = WeekViewModel(
        plan, FakeRecipeRepository(), FakePlanCalendar(), groceries = groceries, flags = flags(on), pantry = pantry,
        decisions = decisions, preferences = FakeAppPreferences()
    )

    private suspend fun planned(
        recipeId: Long, day: Long, type: Long, vararg lines: String, servings: Int? = null, yield: String? = null
    ): Long {
        plan.lines[recipeId] = lines.toList()
        plan.addRecipe(recipeId, day, type, servings)
        val id = plan.meals.value.last().id
        groceries.planned = groceries.planned +
            PlannedIngredients(id, day, servings, recipeId, "Recipe $recipeId", lines.toList(), yield, "en")
        return id
    }

    @Test
    fun `Add to groceries adds exactly the missing lines at the planned servings, and Undo takes them off`() =
        runTest(mainDispatcherRule.dispatcher) {
            pantry.items.value = listOf(item(1, "flour"))
            groceries.add(listOf(NewGroceryLine("milk", "en")))
            val falafel = planned(
                7, today, LUNCH, "For the sauce:", "2 tbsp tahini", "1 tsp sumac", "1 cup flour", "salt and pepper to taste",
                servings = 4, yield = "2 servings"
            )
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(PantryGap(listOf("tahini", "sumac")), vm.uiState.value.pantryGaps[falafel])

            vm.onAddMissingToGroceries(falafel)
            advanceUntilIdle()
            assertEquals(listOf("milk", "4 tbsp tahini", "2 tsp sumac"), groceries.items.value.map { it.text })
            assertEquals(listOf(null, 7L, 7L), groceries.items.value.map { it.recipeId })
            assertEquals(2, vm.uiState.value.addedToGroceries?.count)
            // Seen to: the note says so instead of naming them again.
            assertEquals(PantryGap(emptyList(), onList = 2), vm.uiState.value.pantryGaps[falafel])
            assertTrue(vm.uiState.value.pantryGaps.getValue(falafel).allOnList)

            vm.onUndoAddToGroceries()
            advanceUntilIdle()
            assertEquals(listOf("milk"), groceries.items.value.map { it.text })
            assertEquals(null, vm.uiState.value.addedToGroceries)
            assertEquals(PantryGap(listOf("tahini", "sumac")), vm.uiState.value.pantryGaps[falafel])
        }

    @Test
    fun `the note follows the grocery list, and Done shopping's restock ends it`() = runTest(mainDispatcherRule.dispatcher) {
        pantry.items.value = listOf(item(1, "flour"))
        val falafel = planned(7, today, LUNCH, "2 tbsp tahini", "1 tsp sumac")
        val vm = viewModel()
        advanceUntilIdle()

        groceries.add(listOf(NewGroceryLine("tahini", "en")))
        advanceUntilIdle()
        assertEquals(PantryGap(listOf("sumac"), onList = 1), vm.uiState.value.pantryGaps[falafel])

        // Only the rest is added.
        vm.onAddMissingToGroceries(falafel)
        advanceUntilIdle()
        assertEquals(listOf("tahini", "1 tsp sumac"), groceries.items.value.map { it.text })
        assertEquals(1, vm.uiState.value.addedToGroceries?.count)
        vm.onAddedSnackbarDismissed()

        // Ticked is not on the list any more: named again until the pantry has it.
        groceries.setChecked(groceries.items.value.map { it.id }, true)
        advanceUntilIdle()
        assertEquals(PantryGap(listOf("tahini", "sumac")), vm.uiState.value.pantryGaps[falafel])
        pantry.items.value = pantry.items.value + item(2, "tahini") + item(3, "sumac")
        advanceUntilIdle()
        assertTrue(falafel !in vm.uiState.value.pantryGaps)
    }

    @Test
    fun `a removal settles the add's undo, one undo at a time`() = runTest(mainDispatcherRule.dispatcher) {
        pantry.items.value = listOf(item(1, "flour"))
        val falafel = planned(7, today, LUNCH, "2 tbsp tahini")
        val other = planned(8, today, DINNER, "2 cups flour")
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAddMissingToGroceries(falafel)
        advanceUntilIdle()
        vm.onRemove(plan.meals.value.first { it.id == other })
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.addedToGroceries)
        vm.onUndoAddToGroceries() // nothing left to undo
        advanceUntilIdle()
        assertEquals(listOf("2 tbsp tahini"), groceries.items.value.map { it.text })
    }

    @Test
    fun `a planned recipe missing ingredients gets the note, one with everything none`() = runTest(mainDispatcherRule.dispatcher) {
        pantry.items.value = listOf(item(1, "flour"), item(2, "eggs"))
        val falafel = planned(7, today, LUNCH, "2 cups flour", "2 tbsp tahini", "1 tsp sumac")
        val pancakes = planned(8, today, DINNER, "2 cups flour", "2 eggs")
        plan.addNote("Eat out", today + 1, DINNER)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(mapOf(falafel to PantryGap(listOf("tahini", "sumac"))), vm.uiState.value.pantryGaps)
        assertTrue(pancakes !in vm.uiState.value.pantryGaps)
    }

    @Test
    fun `the note follows the pantry and the plan`() = runTest(mainDispatcherRule.dispatcher) {
        pantry.items.value = listOf(item(1, "flour"))
        val falafel = planned(7, today, LUNCH, "2 tbsp tahini")
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(setOf(falafel), vm.uiState.value.pantryGaps.keys)

        pantry.items.value = pantry.items.value + item(2, "tahini")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pantryGaps.isEmpty())

        // Run out: missing again. Then a new meal joins in.
        pantry.items.value = listOf(item(1, "flour"), item(2, "tahini", inStock = false))
        val curry = planned(9, today + 2, DINNER, "1 can coconut milk")
        advanceUntilIdle()
        assertEquals(setOf(falafel, curry), vm.uiState.value.pantryGaps.keys)

        vm.onRemove(plan.meals.value.first { it.id == curry })
        advanceUntilIdle()
        assertEquals(setOf(falafel), vm.uiState.value.pantryGaps.keys)
    }

    @Test
    fun `an empty pantry, a meal in the past and the flag off say nothing`() = runTest(mainDispatcherRule.dispatcher) {
        planned(7, today, LUNCH, "2 tbsp tahini")
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pantryGaps.isEmpty()) // no pantry kept

        pantry.items.value = listOf(item(1, "flour"))
        val yesterday = planned(8, today - 1, LUNCH, "2 tbsp tahini")
        advanceUntilIdle()
        assertTrue(yesterday !in vm.uiState.value.pantryGaps)
        assertEquals(1, vm.uiState.value.pantryGaps.size)

        val off = viewModel(on = false)
        advanceUntilIdle()
        assertTrue(off.uiState.value.pantryGaps.isEmpty())
    }

    @Test
    fun `the model's same answer counts as had, as in What I need`() = runTest(mainDispatcherRule.dispatcher) {
        pantry.items.value = listOf(item(1, "green onion"))
        val meal = planned(7, today, LUNCH, "2 scallions")
        val decisions = FakeDecisionRepository()
        val vm = viewModel(decisions = decisions)
        advanceUntilIdle()
        assertEquals(setOf(meal), vm.uiState.value.pantryGaps.keys)

        decisions.answer(DecisionQuestion.sameIngredient("scallions", "green onion", "en") to "same")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pantryGaps.isEmpty())
    }
}
