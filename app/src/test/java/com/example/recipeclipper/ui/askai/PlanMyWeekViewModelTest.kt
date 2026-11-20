package com.example.recipeclipper.ui.askai

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.AskAiPrompt
import com.example.recipeclipper.data.model.AskAiPrompt.PlanSource
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanChoices
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Plan my week with choices (#392, #367), the sheet's ViewModel over fakes. iOS: `AskAiViewModelTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanMyWeekViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = 1_800_000_000_000L
    private val today = PlanDays.today(now)

    private val pantry = FakePantryRepository()
    private val lists = FakeListRepository()
    private val recipes = FakeRecipeRepository()
    private val preferences = FakeAppPreferences()
    private val mealPlan = FakeMealPlanRepository()
    private val choices = FakePlanChoices()
    private val words = AskAiPrompt.Words.ENGLISH

    private fun flags(on: Boolean) =
        FeatureFlags(FakeFeatureFlagStore(mapOf(Flag.PLAN_MY_WEEK_PLUS.key to on)), FlagRegistry.definitions, isDebug = true)

    private fun viewModel(on: Boolean = true) =
        AskAiViewModel(pantry, lists, recipes, preferences, Clock { now }, null, mealPlan, choices, flags(on))

    private fun TestScope.start(on: Boolean = true): AskAiViewModel {
        val vm = viewModel(on)
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        vm.onKind(AskAiKind.PLAN_MY_WEEK)
        advanceUntilIdle()
        return vm
    }

    private fun summary(id: Long, title: String, link: String = "https://example.com/$id", viewed: Long = id) = RecipeSummary(
        id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = viewed, isSaved = true, sourceUrl = link
    )

    private val dinner = "dinner"

    @Test fun `without the flag the sheet and the prompt are today's week of dinners`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = start(on = false)
        assertFalse(vm.uiState.value.plan)
        assertTrue(vm.uiState.value.canSend)
        val prompt = vm.prompt(words)!!
        assertTrue(prompt.startsWith("Plan one dinner recipe for each of these days:"))
        assertFalse(prompt.contains("\"meal\""))
        // The choices do nothing: a tick leaves no trace and no state.
        vm.onMeal("breakfast")
        advanceUntilIdle()
        assertTrue(vm.prompt(words)!!.startsWith("Plan one dinner recipe"))
    }

    @Test fun `Dinner is ticked at first, the start day is today and the recipes are new ones`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = start()
            val state = vm.uiState.value
            assertTrue(state.plan)
            assertEquals(setOf(dinner), state.chosenMeals)
            assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snack"), state.mealTypes.map { it.name })
            assertEquals(today, state.startDay)
            assertEquals((0 until PlanDays.SHEET_DAYS).map { today + it }, state.days)
            assertEquals(PlanSource.NEW, state.source)
            val prompt = vm.prompt(words)!!
            assertTrue(prompt.startsWith("Plan one recipe for each of these meals (Dinner) on each of these days:"))
            assertTrue(prompt.contains("- ${AskAiPrompt.isoDate(today)} ("))
            assertTrue(prompt.contains("\"meal\", holding the meal's name exactly as listed."))
            assertFalse(prompt.contains("saved recipes"))
        }

    @Test fun `the chosen meals are listed in the cook's order, by their current names, and remembered by key`() =
        runTest(mainDispatcherRule.dispatcher) {
            mealPlan.types.value = mealPlan.types.value.map { if (it.builtInKey == MealType.DINNER) it.copy(name = "Abendessen") else it }
            val vm = start()
            vm.onMeal("lunch")
            vm.onMeal("breakfast")
            advanceUntilIdle()
            assertEquals(setOf("breakfast", "lunch", dinner), vm.uiState.value.chosenMeals)
            // The types' order, not the order of the ticks; their names as they are now.
            assertTrue(vm.prompt(words)!!.startsWith("Plan one recipe for each of these meals (Breakfast, Lunch, Abendessen)"))
            assertEquals(listOf("breakfast", "lunch", dinner), choices.planMealKeys)

            vm.onMeal(dinner)
            advanceUntilIdle()
            assertEquals(listOf("breakfast", "lunch"), choices.planMealKeys)
            assertTrue(vm.prompt(words)!!.startsWith("Plan one recipe for each of these meals (Breakfast, Lunch)"))
        }

    @Test fun `the last choice is what the sheet opens with next time, but the start day is not`() =
        runTest(mainDispatcherRule.dispatcher) {
            val first = start()
            first.onMeal("breakfast")
            first.onStartDay(today + 4)
            advanceUntilIdle()
            assertEquals(today + 4, first.uiState.value.startDay)
            assertTrue(first.prompt(words)!!.contains("- ${AskAiPrompt.isoDate(today + 4)} ("))

            val second = start()
            assertEquals(setOf("breakfast", dinner), second.uiState.value.chosenMeals)
            assertEquals(today, second.uiState.value.startDay)

            // Closing the sheet starts it over: today again, the meals as last chosen.
            first.onReset()
            advanceUntilIdle()
            assertEquals(today, first.uiState.value.startDay)
            assertEquals(setOf("breakfast", dinner), first.uiState.value.chosenMeals)
        }

    @Test fun `a remembered type that is gone falls back to Dinner, and a type of the cook's own is kept by its uid`() =
        runTest(mainDispatcherRule.dispatcher) {
            mealPlan.types.value = mealPlan.types.value + MealType(9, "Brunch", null, 4, uid = "uid-brunch")
            choices.planMealKeys = listOf("uid-brunch", "uid-deleted")
            val vm = start()
            assertEquals(setOf("uid-brunch"), vm.uiState.value.chosenMeals)
            assertTrue(vm.prompt(words)!!.startsWith("Plan one recipe for each of these meals (Brunch)"))

            mealPlan.types.value = mealPlan.types.value.filter { it.id != 9L }
            advanceUntilIdle()
            assertEquals(setOf(dinner), vm.uiState.value.chosenMeals)

            choices.planMealKeys = listOf("gone")
            assertEquals(setOf(dinner), start().uiState.value.chosenMeals)
        }

    @Test fun `with no meal ticked nothing can be sent, and no more meals are ticked than a plan holds`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = start()
            vm.onMeal(dinner)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.chosenMeals.isEmpty())
            assertFalse(vm.uiState.value.canSend)
            assertNull(vm.prompt(words))

            mealPlan.types.value = (1..12).map { MealType(it.toLong(), "M$it", null, it, uid = "u$it") }
            advanceUntilIdle()
            val many = start()
            (1..12).forEach { many.onMeal("u$it") }
            advanceUntilIdle()
            assertEquals(AskAiViewModel.MAX_MEALS, many.uiState.value.chosenMeals.size)
            assertEquals(10, AskAiViewModel.MAX_MEALS)
        }

    @Test fun `from my recipes names them by link, the most recently viewed first, never the tour's sample`() =
        runTest(mainDispatcherRule.dispatcher) {
            recipes.history.value = listOf(
                summary(1, "Old stew", viewed = 10),
                summary(2, "Fresh soup", viewed = 30),
                summary(3, "Tour", link = "manual:sample", viewed = 99),
                summary(4, "Typed pie", link = "manual:abc-1", viewed = 20)
            )
            val vm = start()
            vm.onSource(PlanSource.MINE)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(3, state.recipeCount)
            assertEquals(3, state.recipesSent)
            assertTrue(state.canSend)
            val prompt = vm.prompt(words)!!
            assertTrue(
                prompt.contains(
                    "- Fresh soup | https://example.com/2\n- Typed pie | manual:abc-1\n- Old stew | https://example.com/1"
                )
            )
            assertFalse(prompt.contains("Tour"))
            assertTrue(prompt.contains("Use only my saved recipes listed above."))
        }

    @Test fun `the names sent are capped, and the sheet counts them`() = runTest(mainDispatcherRule.dispatcher) {
        recipes.history.value = (1L..60L).map { summary(it, "Dish $it") }
        val vm = start()
        vm.onSource(PlanSource.MIX)
        advanceUntilIdle()
        assertEquals(60, vm.uiState.value.recipeCount)
        assertEquals(40, vm.uiState.value.recipesSent)
        val prompt = vm.prompt(words)!!
        assertTrue(prompt.contains("- Dish 60 | https://example.com/60"))
        assertTrue(prompt.contains("- Dish 21 | https://example.com/21"))
        assertFalse(prompt.contains("Dish 20 |"))
    }

    @Test fun `one list is the source when chosen, and a list that goes is forgotten`() =
        runTest(mainDispatcherRule.dispatcher) {
            lists.lists.value = listOf(
                RecipeList(1, "Dinner", isBuiltIn = true, isFavorites = false, recipeCount = 0),
                RecipeList(2, "Empty", isBuiltIn = false, isFavorites = false, recipeCount = 0)
            )
            lists.membership.value = setOf(5L to 1L)
            recipes.history.value = listOf(summary(5, "Listed"), summary(6, "Unlisted"))
            lists.recipesByList.value = mapOf(1L to listOf(summary(5, "Listed")))
            val vm = start()
            vm.onSource(PlanSource.MINE)
            advanceUntilIdle()
            // Only the lists holding a recipe are offered.
            assertEquals(listOf(1L), vm.uiState.value.planLists.map { it.id })
            assertEquals(2, vm.uiState.value.recipeCount)

            vm.onList(1L)
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.recipeCount)
            val prompt = vm.prompt(words)!!
            assertTrue(prompt.contains("- Listed | https://example.com/5"))
            assertFalse(prompt.contains("Unlisted"))

            lists.membership.value = emptySet()
            advanceUntilIdle()
            assertNull(vm.uiState.value.listId)
            assertEquals(2, vm.uiState.value.recipeCount)
        }

    @Test fun `with no recipes to name, From my recipes and a mix cannot be sent, new recipes can`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = start()
            vm.onSource(PlanSource.MINE)
            advanceUntilIdle()
            assertEquals(0, vm.uiState.value.recipeCount)
            assertFalse(vm.uiState.value.canSend)
            assertNull(vm.prompt(words))
            vm.onSource(PlanSource.MIX)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.canSend)
            vm.onSource(PlanSource.NEW)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canSend)
        }

    @Test fun `a mix keeps its count between one and the week's meals`() = runTest(mainDispatcherRule.dispatcher) {
        recipes.history.value = listOf(summary(1, "A"))
        val vm = start()
        vm.onSource(PlanSource.MIX)
        advanceUntilIdle()
        // One meal a day: seven in the week.
        assertEquals(3, vm.uiState.value.mix)
        vm.onMix(50)
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.mix)
        assertTrue(vm.prompt(words)!!.contains("Use up to 7 of my saved recipes"))
        vm.onMix(0)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.mix)
        vm.onMeal("lunch")
        vm.onMix(14)
        advanceUntilIdle()
        assertEquals(14, vm.uiState.value.mix)
    }

    @Test fun `favourites and cooked lately are ideas and a list to avoid, off when switched off and for my own recipes only`() =
        runTest(mainDispatcherRule.dispatcher) {
            lists.lists.value = listOf(RecipeList(2, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0))
            lists.recipesIn.value = listOf(summary(1, "Lasagne"))
            recipes.history.value = listOf(summary(1, "Lasagne").copy(lastCookedDay = today - 1), summary(2, "Chili", viewed = 5))
            val vm = start()
            var prompt = vm.prompt(words)!!
            assertTrue(prompt.contains("Recipes I like, for ideas: Lasagne."))
            assertTrue(prompt.contains("don't repeat them: Lasagne."))

            vm.onUseFavourites(false)
            vm.onSkipCooked(false)
            advanceUntilIdle()
            prompt = vm.prompt(words)!!
            assertFalse(prompt.contains("Recipes I like"))
            assertFalse(prompt.contains("don't repeat"))

            vm.onUseFavourites(true)
            vm.onSkipCooked(true)
            vm.onSource(PlanSource.MINE)
            advanceUntilIdle()
            prompt = vm.prompt(words)!!
            assertFalse(prompt.contains("Recipes I like"))
            assertFalse(prompt.contains("don't repeat"))
            vm.onSource(PlanSource.MIX)
            advanceUntilIdle()
            assertTrue(vm.prompt(words)!!.contains("Recipes I like, for ideas: Lasagne."))
        }

    @Test fun `many meals to write out warn that the answer may be cut off`() = runTest(mainDispatcherRule.dispatcher) {
        recipes.history.value = listOf(summary(1, "A"))
        val vm = start()
        assertFalse(vm.uiState.value.longAnswer) // 7
        vm.onMeal("lunch")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.longAnswer) // 14
        vm.onMeal("breakfast")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.longAnswer) // 21
        vm.onSource(PlanSource.MINE)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.longAnswer) // named, not written out
    }
}
