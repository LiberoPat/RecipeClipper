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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

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

    /** Counts the matchings handed to the background dispatcher (#332). */
    private class CountingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            inner.dispatch(context, block)
        }
    }

    private val matching = CountingDispatcher(mainDispatcherRule.dispatcher)

    private fun viewModel() = UseItUpViewModel(pantry, lists, plan, calendar, flags, inbox, matching)

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

    // #332: the tab's ViewModel outlives the day; the window and "Plan today" follow the real one.
    @Test fun `today follows the real day when the Pantry resumes or the reminder is tapped`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        calendar.today = today + 1
        vm.onScreenResumed()
        advanceUntilIdle()
        // Milk expired yesterday; spinach is due today.
        assertEquals(today + 1, vm.uiState.value.today)
        assertEquals(mapOf(1L to 2), vm.uiState.value.counts)

        calendar.today = today + 2
        inbox.offer()
        advanceUntilIdle()
        assertEquals(today + 2, vm.uiState.value.today)
        assertNull(vm.uiState.value.sheet)
        assertEquals(emptyMap<Long, Int>(), vm.uiState.value.counts)
    }

    // #332: a reminder with nothing to show is forgotten at once, so a recipe saved later never
    // pops the sheet up by itself.
    @Test fun `a request nothing answers is dropped, and never opens the sheet later`() = runTest(mainDispatcherRule.dispatcher) {
        lists.savedRecipes.value = emptyList()
        val vm = viewModel()
        inbox.offer()
        advanceUntilIdle()
        assertNull(vm.uiState.value.sheet)
        lists.savedRecipes.value = listOf(SavedRecipe(10, "Saag", null, "en", listOf("500 g spinach")))
        advanceUntilIdle()
        assertEquals(mapOf(1L to 1), vm.uiState.value.counts)
        assertNull(vm.uiState.value.sheet)
    }

    // #332: a write that failed frees the tap, so it can be tried again.
    @Test fun `a failed plan can be tried again`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        plan.failAdds = true
        vm.onPlan(10, today)
        advanceUntilIdle()
        assertEquals(0, plan.meals.value.size)
        plan.failAdds = false
        vm.onPlan(10, today)
        advanceUntilIdle()
        assertEquals(listOf(10L to today), plan.meals.value.map { it.recipeId to it.day })
    }

    // #332: the groups are matched off the main thread, once per change of what they're made
    // from; a write to the plan reuses them.
    @Test fun `matching runs on the background dispatcher, and not again for a plan change`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(1, matching.dispatches)
        vm.onPlan(10, today)
        advanceUntilIdle()
        assertEquals(setOf(10L to today), vm.uiState.value.planned)
        assertEquals(1, matching.dispatches)
        pantry.items.value = pantry.items.value.filter { it.id != 2L }
        advanceUntilIdle()
        assertEquals(mapOf(1L to 2), vm.uiState.value.counts)
        assertEquals(2, matching.dispatches)
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
