package com.example.recipeclipper.ui.week

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Shop for this week in two trips (#396): the split, the two Add buttons, and the flag. iOS: `FreshShopTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatINeedFreshViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = FakePlanCalendar.WEDNESDAY
    private val calendar = FakePlanCalendar()

    private val groceries = FakeGroceryRepository().apply {
        planned = listOf(
            PlannedIngredients(1, day = today + 1, servings = null, recipeId = 7, title = "Pasta",
                ingredients = listOf("1 lb spaghetti", "2 tbsp olive oil", "1 bag baby spinach"), yield = null, language = "en"),
            PlannedIngredients(2, day = today + 2, servings = null, recipeId = 8, title = "Fish tacos",
                ingredients = listOf("1 lb cod fillets", "8 tortillas"), yield = null, language = "en"),
            PlannedIngredients(3, day = today + 5, servings = null, recipeId = 9, title = "Salmon bowls",
                ingredients = listOf("2 salmon fillets", "1 bunch basil", "1 cup rice", "4 oz smoked salmon"), yield = null, language = "en"),
            PlannedIngredients(4, day = today + 6, servings = null, recipeId = 10, title = "Spinach salad",
                ingredients = listOf("1 bag baby spinach"), yield = null, language = "en")
        )
    }

    private fun flags(on: Boolean = true) =
        FeatureFlags(FakeFeatureFlagStore(mapOf("freshFood" to on)), FlagRegistry.definitions, isDebug = true)

    private fun viewModel(
        pantry: FakePantryRepository = FakePantryRepository(),
        flags: FeatureFlags? = flags(),
        calendar: FakePlanCalendar? = this.calendar
    ) = WhatINeedViewModel(
        SavedStateHandle(mapOf(WhatINeedViewModel.WEEK_START_ARG to today)), groceries, pantry, FakeAppPreferences(),
        decisions = null, calendar = calendar, flags = flags
    )

    private fun item(id: Long, name: String) =
        PantryItem(id, name, null, "en", Aisle.OTHER, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = null)

    @Test
    fun `a perishable only for meals three or more days away goes later, each with the day it is for`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            val trips = vm.uiState.value.trips!!
            assertEquals(listOf("salmon fillets", "basil"), trips.later.map { it.row.name })
            assertEquals(listOf(today + 5, today + 5), trips.later.map { it.firstDay })
            assertEquals(listOf("fish", "herbs"), trips.later.map { it.group.key })
            // The spinach is also for tomorrow's pasta, the cod two days away, smoked salmon isn't fresh.
            assertEquals(
                listOf("spaghetti", "olive oil", "baby spinach", "cod fillets", "tortillas", "rice", "smoked salmon"),
                trips.now.map { it.name }
            )
        }

    @Test
    fun `the amounts and lines are exactly what What I need shows`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("2 salmon fillets"), state.trips!!.later[0].row.lines.map { it.text })
        assertEquals(state.needs!!.buy.toSet(), (state.trips!!.now + state.trips!!.later.map { it.row }).toSet())
        assertEquals(state.needs!!.buy.size, state.trips!!.now.size + state.trips!!.later.size)
    }

    @Test
    fun `Add to groceries adds Buy now's lines, and the later group has its own button, each once`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onAddBuyToGroceries()
            advanceUntilIdle()
            val now = groceries.items.value.map { it.text }
            assertEquals(
                listOf("1 lb spaghetti", "2 tbsp olive oil", "1 bag baby spinach", "1 bag baby spinach", "1 lb cod fillets", "8 tortillas", "1 cup rice", "4 oz smoked salmon").sorted(),
                now.sorted()
            )
            assertTrue(vm.uiState.value.added)
            assertFalse(vm.uiState.value.addedLater)

            vm.onAddBuyToGroceries() // once
            vm.onAddLaterToGroceries()
            vm.onAddLaterToGroceries()
            advanceUntilIdle()
            assertEquals(now.size + 2, groceries.items.value.size)
            assertEquals(listOf("2 salmon fillets", "1 bunch basil"), groceries.items.value.map { it.text }.takeLast(2))
            assertTrue(vm.uiState.value.addedLater)
        }

    @Test
    fun `an ingredient in the pantry is Have, not later`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakePantryRepository(listOf(item(1, "basil"))))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("salmon fillets"), state.trips!!.later.map { it.row.name })
        assertTrue(state.needs!!.have.any { it.name == "basil" })
    }

    @Test
    fun `the split follows the pantry live`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository()
        val vm = viewModel(pantry)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.trips!!.later.size)

        pantry.add(com.example.recipeclipper.data.model.NewPantryItem("salmon fillets", "en"))
        advanceUntilIdle()
        assertEquals(listOf("basil"), vm.uiState.value.trips!!.later.map { it.row.name })
    }

    @Test
    fun `with the flag off, or without today, To buy is one list and Add adds all of it`() =
        runTest(mainDispatcherRule.dispatcher) {
            for (vm in listOf(viewModel(flags = flags(on = false)), viewModel(calendar = null), viewModel(flags = null))) {
                advanceUntilIdle()
                assertNull(vm.uiState.value.trips)
                assertEquals(vm.uiState.value.needs!!.buy, vm.uiState.value.buyNow)
            }
            val vm = viewModel(flags = flags(on = false))
            advanceUntilIdle()
            vm.onAddBuyToGroceries()
            advanceUntilIdle()
            assertTrue(groceries.items.value.any { it.text == "2 salmon fillets" })
            assertTrue(groceries.items.value.any { it.text == "1 bunch basil" })
        }

    @Test
    fun `nothing is later when every perishable is needed sooner`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.today = today + 3 // the salmon is now two days away
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.trips!!.later.isEmpty())
        assertEquals(vm.uiState.value.needs!!.buy, vm.uiState.value.trips!!.now)
    }
}
