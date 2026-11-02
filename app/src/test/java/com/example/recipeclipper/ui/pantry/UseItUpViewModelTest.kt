package com.example.recipeclipper.ui.pantry

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.SavedRecipe
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UseItUpViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val calendar = FakePlanCalendar()
    private val today = calendar.today()
    private val lists = FakeListRepository()
    private val plan = FakeMealPlanRepository()
    private val store = FakeFeatureFlagStore()
    private val flags = FeatureFlags(store, FlagRegistry.definitions, isDebug = false)
    private val inbox = UseItUpInbox()

    private fun item(id: Long, name: String, expires: Long?) =
        PantryItem(id, name, null, "en", Aisle.OTHER, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = expires)

    private val spinach = item(1, "spinach", today + 1)
    private val milk = item(2, "milk", today)
    private val flour = item(3, "flour", today + 5)
    private val pantry = FakePantryRepository(listOf(spinach, milk, flour))

    private fun viewModel() = UseItUpViewModel(pantry, lists, plan, calendar, flags, inbox)

    init {
        lists.savedRecipes.value = listOf(
            SavedRecipe(10, "Saag", null, "en", listOf("500 g spinach")),
            SavedRecipe(11, "Pancakes", null, "en", listOf("1 cup milk", "2 cups flour")),
            SavedRecipe(12, "Spinach pie", null, "en", listOf("For the filling:", "300 g spinach"))
        )
    }

    @Test fun `rows expiring soon count the saved recipes that use them`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        // Flour expires in five days: outside the reminder's window, so no line.
        assertEquals(mapOf(1L to 2, 2L to 1), vm.uiState.value.counts)
        assertNull(vm.uiState.value.sheet)
    }

    @Test fun `a row's line opens the sheet on that item only`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.onOpen(1)
        advanceUntilIdle()
        val sheet = vm.uiState.value.sheet!!
        assertEquals(listOf(spinach), sheet.map { it.item })
        assertEquals(listOf(10L, 12L), sheet.single().recipes.map { it.id })

        vm.onDismiss()
        advanceUntilIdle()
        assertNull(vm.uiState.value.sheet)
    }

    @Test fun `the reminder opens the sheet on everything due, soonest first`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        inbox.offer()
        advanceUntilIdle()
        assertEquals(listOf(milk, spinach), vm.uiState.value.sheet!!.map { it.item })
        assertEquals(false, inbox.take())
    }

    @Test fun `one tap plans the recipe as dinner, once`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPlan(10, today + 1)
        vm.onPlan(10, today + 1)
        advanceUntilIdle()
        val meal = plan.meals.value.single()
        assertEquals(10L, meal.recipeId)
        assertEquals(today + 1, meal.day)
        assertEquals(FakeMealPlanRepository.DINNER, meal.mealTypeId)
        assertNull(meal.servings)
        assertEquals(setOf(10L to today + 1), vm.uiState.value.planned)

        vm.onPlan(10, today + 1)
        advanceUntilIdle()
        assertEquals(1, plan.meals.value.size)
    }

    @Test fun `off, nothing shows`() = runTest(mainDispatcherRule.dispatcher) {
        flags.set(Flag.USE_IT_UP, false)
        val vm = viewModel()
        vm.onOpen(1)
        advanceUntilIdle()
        assertEquals(emptyMap<Long, Int>(), vm.uiState.value.counts)
        assertNull(vm.uiState.value.sheet)
    }
}
