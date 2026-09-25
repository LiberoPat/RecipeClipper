package com.example.recipeclipper.ui.week

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.NeedStatus
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
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
class WhatINeedViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groceries = FakeGroceryRepository().apply {
        planned = listOf(
            PlannedIngredients(1, day = 100, servings = 8, recipeId = 7, title = "Pancakes",
                ingredients = listOf("2 cups flour", "2 eggs", "1 tsp salt"), yield = "Serves 4", language = "en"),
            PlannedIngredients(2, day = 103, servings = null, recipeId = 9, title = "Bread",
                ingredients = listOf("500 g flour"), yield = null, language = "en"),
            PlannedIngredients(3, day = 107, servings = null, recipeId = 5, title = "Next week",
                ingredients = listOf("1 onion"), yield = null, language = "en")
        )
    }

    private fun item(id: Long, name: String, inStock: Boolean = true, alwaysHave: Boolean = false) =
        PantryItem(id, name, null, "en", Aisle.OTHER, inStock, alwaysHave, null, null)

    private fun viewModel(pantry: FakePantryRepository, system: UnitSystem = UnitSystem.AS_WRITTEN) = WhatINeedViewModel(
        SavedStateHandle(mapOf(WhatINeedViewModel.WEEK_START_ARG to 100L)), groceries, pantry, FakeAppPreferences(unitSystem = system),
    )

    @Test
    fun `the week's lines at planned servings, marked Have or Buy, staples out of Buy`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "flour"), item(2, "salt", inStock = false, alwaysHave = true)))
        val vm = viewModel(pantry)
        assertNull(vm.uiState.value.needs)
        advanceUntilIdle()

        val needs = vm.uiState.value.needs!!
        assertEquals(listOf("eggs"), needs.buy.map { it.name })
        assertEquals(listOf("4 eggs"), needs.buy.single().lines.map { it.text }) // doubled: 8 of 4 servings
        assertEquals(listOf("flour", "salt"), needs.have.map { it.name })
        assertEquals(listOf("4 cups flour", "500 g flour"), needs.have[0].lines.map { it.text })
        assertEquals(NeedStatus.STAPLE, needs.have[1].status)
    }

    @Test
    fun `the pantry changing moves a row across`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "eggs", inStock = false)))
        val vm = viewModel(pantry)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needs!!.buy.any { it.name == "eggs" })

        pantry.setInStock(listOf(1), true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needs!!.have.any { it.name == "eggs" })
    }

    @Test
    fun `adding Buy puts only the Buy lines on groceries, with recipe and day, once`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakePantryRepository(listOf(item(1, "flour"))))
        advanceUntilIdle()

        vm.onAddBuyToGroceries()
        vm.onAddBuyToGroceries()
        advanceUntilIdle()

        assertEquals(listOf("4 eggs", "2 tsp salt"), groceries.items.value.map { it.text })
        assertEquals(listOf(7L, 7L), groceries.items.value.map { it.recipeId })
        assertEquals(listOf(100L, 100L), groceries.items.value.map { it.plannedDay })
        assertTrue(vm.uiState.value.added)
    }

    @Test
    fun `nothing to buy adds nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakePantryRepository(listOf(item(1, "flour"), item(2, "eggs"), item(3, "salt"))))
        advanceUntilIdle()
        vm.onAddBuyToGroceries()
        advanceUntilIdle()
        assertTrue(groceries.items.value.isEmpty())
        assertFalse(vm.uiState.value.added)
    }
}
