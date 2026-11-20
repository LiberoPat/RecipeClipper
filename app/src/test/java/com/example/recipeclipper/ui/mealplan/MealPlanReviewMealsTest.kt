package com.example.recipeclipper.ui.mealplan

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.CutOffMessage
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.remote.MealPlanImport
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
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

/** The review with Plan my week's choices (#392, #367): meals, recipes already saved, links that match nothing. iOS: `MealPlanReviewTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealPlanReviewMealsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val calendar = FakePlanCalendar()
    private val today = calendar.today
    private val inbox = SharedTextInbox()
    private val recipes = FakeRecipeRepository()
    private val week = FakeMealPlanRepository()
    private val policy = FakeLibraryPolicy(LibraryLimit.Unlimited)

    private fun flags(on: Boolean) =
        FeatureFlags(FakeFeatureFlagStore(mapOf(Flag.PLAN_MY_WEEK_PLUS.key to on)), FlagRegistry.definitions, isDebug = true)

    private fun written(name: String, day: Long?, meal: String?) = MealPlanImport.Item(
        Recipe(
            name = name, image = null, ingredients = listOf("1 egg"), instructions = listOf("Cook."),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "", language = "en"
        ),
        day, meal
    )

    private fun named(name: String, link: String, day: Long?, meal: String?) = MealPlanImport.Item(
        Recipe(
            name = name, image = null, ingredients = emptyList(), instructions = emptyList(),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = ""
        ),
        day, meal, link
    )

    private fun saved(id: Long, title: String, link: String) = RecipeSummary(
        id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = id, isSaved = true, sourceUrl = link
    )

    private fun viewModel(items: List<MealPlanImport.Item>, on: Boolean = true, cutOff: Boolean = false): MealPlanReviewViewModel {
        val key = inbox.offerPlan("text", MealPlanImport.Plan(items, cutOff))
        return MealPlanReviewViewModel(
            SavedStateHandle(mapOf(MealPlanReviewViewModel.PLAN_ARG to key)),
            recipes, week, calendar, policy, FakeEntitlements(), inbox, mainDispatcherRule.dispatcher, flags(on)
        )
    }

    private val breakfast = FakeMealPlanRepository.BREAKFAST
    private val lunch = FakeMealPlanRepository.LUNCH
    private val dinner = FakeMealPlanRepository.DINNER

    @Test fun `each row takes the meal type its meal names, by name, else Dinner`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(
            listOf(
                written("Porridge", today, "breakfast"), written("Salad", today, " LUNCH "),
                written("Stew", today, "Supper"), written("Soup", today, null)
            )
        )
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state.meals)
        assertEquals(listOf(breakfast, lunch, dinner, dinner), state.items.map { it.mealTypeId })
        assertEquals(week.types.value, state.mealTypes)
    }

    @Test fun `a meal can be changed before Save, and Save plans the recipe under it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(listOf(written("Porridge", today, "Breakfast"), written("Soup", today + 1, "Lunch")))
        advanceUntilIdle()
        vm.onMeal(0, dinner)
        vm.onDay(1, today + 3)
        recipes.addManualQueue.add(Recipe(id = 11, name = "Porridge", image = null, ingredients = listOf("x"), instructions = listOf("y"),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = ""))
        recipes.addManualQueue.add(Recipe(id = 12, name = "Soup", image = null, ingredients = listOf("x"), instructions = listOf("y"),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = ""))
        vm.onSave()
        advanceUntilIdle()
        assertEquals(
            listOf(Triple(11L, today, dinner), Triple(12L, today + 3, lunch)),
            week.meals.value.map { Triple(it.recipeId!!, it.day, it.mealTypeId) }
        )
    }

    @Test fun `a recipe named by its link is already there, planned on Save and never saved again`() =
        runTest(mainDispatcherRule.dispatcher) {
            recipes.history.value = listOf(
                saved(7, "Chicken Adobo", "https://example.com/adobo"), saved(8, "Pancakes", "manual:abc-123")
            )
            val vm = viewModel(
                listOf(
                    named("Adobo as the AI wrote it", "https://example.com/adobo?utm_source=chat", today, "Dinner"),
                    named("Pancakes", "manual:abc-123", today + 1, "Breakfast"),
                    written("New soup", today + 2, "Lunch")
                )
            )
            advanceUntilIdle()
            var state = vm.uiState.value
            assertEquals(listOf("Chicken Adobo", "Pancakes", "New soup"), state.items.map { it.name })
            assertEquals(listOf(7L, 8L, null), state.items.map { it.existingId })
            assertEquals(0, state.notFound)
            assertEquals(3, state.savingCount)

            recipes.addManualQueue.add(Recipe(id = 21, name = "New soup", image = null, ingredients = listOf("x"), instructions = listOf("y"),
                prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = ""))
            vm.onSave()
            advanceUntilIdle()
            // Only the written recipe was saved; the two saved ones were planned as they are.
            assertEquals(listOf("New soup"), recipes.addManualCalls.map { it.name })
            assertEquals(
                listOf(Triple(7L, today, dinner), Triple(8L, today + 1, breakfast), Triple(21L, today + 2, lunch)),
                week.meals.value.map { Triple(it.recipeId!!, it.day, it.mealTypeId) }
            )
            state = vm.uiState.value
            assertEquals(3, state.kept)
            assertEquals(3, state.planned)
            assertTrue(state.finished)
        }

    @Test fun `a link that matches nothing is left out and counted, never made into a recipe`() =
        runTest(mainDispatcherRule.dispatcher) {
            recipes.history.value = listOf(saved(7, "Chicken Adobo", "https://example.com/adobo"))
            val vm = viewModel(
                listOf(
                    named("Adobo", "https://example.com/adobo", today, "Dinner"),
                    named("Made up", "https://example.com/made-up", today + 1, "Dinner"),
                    named("Also made up", "https://example.com/other", today + 2, "Dinner"),
                    written("Soup", today + 3, "Dinner")
                )
            )
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(listOf("Chicken Adobo", "Soup"), state.items.map { it.name })
            assertEquals(2, state.notFound)
            assertTrue(recipes.addManualCalls.isEmpty())
        }

    @Test fun `an existing recipe with no day waits for one and takes no room`() =
        runTest(mainDispatcherRule.dispatcher) {
            policy.limit = LibraryLimit.Capped(50)
            recipes.count.value = 50
            recipes.history.value = listOf(saved(7, "Chicken Adobo", "https://example.com/adobo"))
            val vm = viewModel(listOf(named("Adobo", "https://example.com/adobo", null, "Dinner"), written("Soup", today, "Dinner")))
            advanceUntilIdle()
            var state = vm.uiState.value
            // The library is full: the new recipe doesn't fit, the existing one is no matter.
            assertEquals(listOf(true, false), state.items.map { it.fits })
            assertEquals(0, state.savingCount)

            vm.onDay(0, today + 1)
            assertEquals(1, vm.uiState.value.savingCount)
            vm.onSave()
            advanceUntilIdle()
            assertTrue(recipes.addManualCalls.isEmpty())
            assertEquals(listOf(7L to today + 1), week.meals.value.map { it.recipeId!! to it.day })
            state = vm.uiState.value
            assertEquals(listOf("Soup"), state.items.map { it.name })
        }

    @Test fun `a failed plan write leaves the existing recipe listed`() = runTest(mainDispatcherRule.dispatcher) {
        recipes.history.value = listOf(saved(7, "Chicken Adobo", "https://example.com/adobo"))
        val vm = viewModel(listOf(named("Adobo", "https://example.com/adobo", today, "Dinner"), written("Soup", today, "Dinner")))
        advanceUntilIdle()
        week.failAdds = true
        vm.onSave()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.saveFailed)
        assertTrue(vm.uiState.value.items.any { it.existingId == 7L })
    }

    @Test fun `without the flag the answer is read as before and a link with no recipe is left out silently`() =
        runTest(mainDispatcherRule.dispatcher) {
            recipes.history.value = listOf(saved(7, "Chicken Adobo", "https://example.com/adobo"))
            val vm = viewModel(
                listOf(written("Porridge", today, "Breakfast"), named("Adobo", "https://example.com/adobo", today, "Dinner"), written("Soup", today, null)),
                on = false
            )
            advanceUntilIdle()
            val state = vm.uiState.value
            assertFalse(state.meals)
            assertEquals(listOf("Porridge", "Soup"), state.items.map { it.name })
            assertEquals(0, state.notFound)
            assertTrue(state.items.all { it.mealTypeId == null })

            recipes.addManualQueue.add(Recipe(id = 1, name = "Porridge", image = null, ingredients = listOf("x"), instructions = listOf("y"),
                prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = ""))
            recipes.addManualQueue.add(Recipe(id = 2, name = "Soup", image = null, ingredients = listOf("x"), instructions = listOf("y"),
                prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = ""))
            vm.onSave()
            advanceUntilIdle()
            assertTrue(week.meals.value.all { it.mealTypeId == dinner })
        }

    @Test fun `the cut-off follow-up names the missing day and meal pairs, from what was read`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(
                listOf(
                    written("A", today, "Breakfast"), written("B", today, "Dinner"),
                    named("C", "https://example.com/c", today + 1, "Breakfast")
                ),
                cutOff = true
            )
            advanceUntilIdle()
            assertTrue(vm.uiState.value.cutOff)
            val words = CutOffMessage.Words({ "days:$it" }, "rest", "recipe", { "pairs:$it" })
            val message = vm.followUp(words)
            assertTrue(message, message.startsWith("pairs:${CutOffMessage.iso(today + 1)} (Dinner), ${CutOffMessage.iso(today + 2)} (Breakfast)"))
            assertNull(null)
            // Without the flag it is the days, as before.
            val old = viewModel(listOf(written("A", today, null), written("B", today + 1, null)), on = false, cutOff = true)
            advanceUntilIdle()
            assertTrue(old.followUp(words).startsWith("days:"))
        }
}
