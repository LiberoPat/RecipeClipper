package com.example.recipeclipper.ui.home

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone

/** The Today card's close button and "Show today's meal" (#448, `todayCardClose`), through [HomeViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayCardCloseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // The test clock's 0 is epoch day 0 everywhere.
    private val zone = TimeZone.getDefault()

    @Before fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = TimeZone.setDefault(zone)

    private val plan = FakeMealPlanRepository()
    private val preferences = FakeAppPreferences()
    private val flagStore = FakeFeatureFlagStore()
    private val flags = FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = true)

    private fun dinner(day: Long = 0) =
        PlannedMeal(day + 1, day, FakeMealPlanRepository.DINNER, 12, "Lemon chicken", imageUrl = null, servings = null, note = null)

    private fun TestScope.home(repository: FakeRecipeRepository = FakeRecipeRepository()): HomeViewModel {
        val vm = HomeViewModel(
            repository, clock = Clock { testScheduler.currentTime }, mealPlan = plan,
            cooked = FakeCookedPhotoRepository(today = 0L), preferences = preferences, featureFlags = flags
        )
        collectEagerly(vm.uiState)
        return vm
    }

    @Test fun `the card is closable with the flag on`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(dinner())
        val vm = home()
        runCurrent()

        assertTrue(vm.uiState.value.todayClosable)
        assertEquals("Lemon chicken", vm.uiState.value.today?.lead?.title)
    }

    @Test fun `closing hides the card for the rest of today and stores today's date`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(dinner())
        val vm = home()
        runCurrent()

        vm.onCloseToday()
        runCurrent()

        assertNull(vm.uiState.value.today)
        assertEquals(0L, preferences.todayCardDismissedDay)

        // Later the same day, still gone.
        advanceTimeBy(20 * 60 * 60 * 1000L)
        runCurrent()
        assertNull(vm.uiState.value.today)
    }

    @Test fun `the card closed yesterday is back today`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(dinner(), dinner(day = 1))
        val vm = home()
        runCurrent()
        vm.onCloseToday()
        runCurrent()
        assertNull(vm.uiState.value.today)

        // Midnight: tomorrow's dinner shows.
        advanceTimeBy(24 * 60 * 60 * 1000L)
        runCurrent()
        assertEquals("Lemon chicken", vm.uiState.value.today?.lead?.title)
        assertEquals("tomorrow's entry", 2L, vm.uiState.value.today?.lead?.entryId)
    }

    @Test fun `a close stored on an earlier day does not hide today's card`() = runTest(mainDispatcherRule.dispatcher) {
        preferences.todayCardDismissedDay = -1L
        plan.meals.value = listOf(dinner())
        val vm = home()
        runCurrent()

        assertEquals("Lemon chicken", vm.uiState.value.today?.lead?.title)
    }

    @Test fun `the setting off hides the card, and on again brings it back`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(dinner())
        val vm = home()
        runCurrent()

        preferences.showTodayCard = false
        runCurrent()
        assertNull(vm.uiState.value.today)

        preferences.showTodayCard = true
        runCurrent()
        assertEquals("Lemon chicken", vm.uiState.value.today?.lead?.title)
    }

    @Test fun `mid-cook the resume is unaffected by the close and the setting`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(dinner())
        val repository = FakeRecipeRepository()
        repository.recent.value = listOf(RecipeSummary(1, "Adobo", null, null, 1, isSaved = false))
        repository.recipes.value = mapOf(
            1L to Recipe(
                name = "Adobo", image = null, ingredients = listOf("1 egg"), instructions = listOf("One", "Two"),
                prepTime = null, cookTime = null, totalTime = null, yield = null,
                sourceUrl = "https://example.com/1", id = 1, cook = CookProgress(active = true, currentStep = 1)
            )
        )
        preferences.showTodayCard = false
        val vm = home(repository)
        runCurrent()
        vm.onCloseToday()
        runCurrent()

        assertEquals(2, vm.uiState.value.resume?.step)
        assertEquals(1L, vm.uiState.value.continueCooking?.id)
    }

    @Test fun `with the flag off there is no close and neither choice counts`() = runTest(mainDispatcherRule.dispatcher) {
        flagStore.setOverride(Flag.TODAY_CARD_CLOSE.key, false)
        preferences.showTodayCard = false
        preferences.todayCardDismissedDay = 0L
        plan.meals.value = listOf(dinner())
        val vm = home()
        runCurrent()

        assertFalse(vm.uiState.value.todayClosable)
        assertEquals("Lemon chicken", vm.uiState.value.today?.lead?.title)
    }

    @Test fun `without preferences or flags the card is as before`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(dinner())
        val vm = HomeViewModel(FakeRecipeRepository(), clock = Clock { testScheduler.currentTime }, mealPlan = plan)
        collectEagerly(vm.uiState)
        runCurrent()

        assertFalse(vm.uiState.value.todayClosable)
        assertEquals("Lemon chicken", vm.uiState.value.today?.lead?.title)
        vm.onCloseToday()
        runCurrent()
        assertEquals("Lemon chicken", vm.uiState.value.today?.lead?.title)
    }
}
