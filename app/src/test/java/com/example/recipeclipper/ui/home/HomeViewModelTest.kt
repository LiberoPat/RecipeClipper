package com.example.recipeclipper.ui.home

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.edit.PasteTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Today is the clock's epoch day in the zone; pinned so the test clock's 0 is day 0 everywhere.
    private val zone = TimeZone.getDefault()

    @Before fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = TimeZone.setDefault(zone)

    private fun summary(id: Long, title: String = "Recipe $id") =
        RecipeSummary(id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = id, isSaved = false)

    @Test fun `continueCooking is the most recent, and nothing else is listed (#371)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            // Newest first, as the repository's real query would order it.
            val summaries = (6 downTo 1).map { summary(it.toLong()) }
            repository.recent.value = summaries
            val vm = HomeViewModel(repository)
            collectEagerly(vm.uiState)
            advanceUntilIdle()

            assertEquals(summaries[0], vm.uiState.value.continueCooking)
        }

    @Test fun `the Ask an AI tile opens its sheet, and dismissing closes it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(FakeRecipeRepository())
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.askAiOpen)

        vm.onAskAi()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.askAiOpen)

        vm.onAskAiDismiss()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.askAiOpen)
    }

    @Test fun `loaded stays false until the repository answers`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = HomeViewModel(repository)

        assertFalse(vm.uiState.value.loaded)

        collectEagerly(vm.uiState)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.loaded)
    }

    // "Paste a link" (#286): the Recipes screen's dialog, opened from Home's tile.

    @Test fun `the paste tile opens the dialog empty, and Go returns the normalised link`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = HomeViewModel(FakeRecipeRepository())
            collectEagerly(vm.uiState)
            advanceUntilIdle()
            assertNull(vm.uiState.value.linkInput)

            vm.onPasteLink()
            advanceUntilIdle()
            assertEquals("", vm.uiState.value.linkInput)
            assertFalse(vm.uiState.value.canOpenLink)

            vm.onLinkChange("example.com/recipe")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canOpenLink)
            var opened: PasteTarget? = null
            vm.onOpenLink { opened = it }
            advanceUntilIdle()
            assertEquals(PasteTarget.Link("https://example.com/recipe"), opened)
            assertNull("the dialog closes", vm.uiState.value.linkInput)
        }

    @Test fun `without the shared-text route, what isn't a link opens nothing, and the dialog stays`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(FakeRecipeRepository())
        collectEagerly(vm.uiState)
        vm.onPasteLink()
        vm.onLinkChange("not a link")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.canOpenLink)
        var opened: PasteTarget? = null
        vm.onOpenLink { opened = it }
        advanceUntilIdle()
        assertNull(opened)
        assertEquals("not a link", vm.uiState.value.linkInput)
        assertTrue(vm.uiState.value.linkNoRecipe)

        vm.onLinkChange("not a link either")
        advanceUntilIdle()
        assertFalse("typing clears the message", vm.uiState.value.linkNoRecipe)

        vm.onLinkDismiss()
        advanceUntilIdle()
        assertNull(vm.uiState.value.linkInput)
    }

    // The Recipes and Lists rows' counts (#286).

    @Test fun `the counts are what Recipes lists and how many lists there are`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            repository.history.value = (1L..3L).map { summary(it) }
            val lists = FakeListRepository()
            lists.lists.value = (1L..6L).map { RecipeList(it, "List $it", true, it == 1L, recipeCount = 0) }
            val vm = HomeViewModel(repository, lists = lists)
            collectEagerly(vm.uiState)
            advanceUntilIdle()

            assertEquals(3, vm.uiState.value.recipeCount)
            assertEquals(6, vm.uiState.value.listCount)
        }

    @Test fun `with no list repository there is no list count`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(FakeRecipeRepository())
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.recipeCount)
        assertNull(vm.uiState.value.listCount)
    }

    // The resume card (#286): the step stopped on and a running timer, only while it's true.

    private fun recipe(id: Long, cook: CookProgress, steps: Int = 4) = Recipe(
        name = "Recipe $id", image = null, ingredients = listOf("1 egg"),
        instructions = (1..steps).map { "Step $it" }, prepTime = null, cookTime = null,
        totalTime = null, yield = null, sourceUrl = "https://example.com/$id", id = id, cook = cook
    )

    /** Recipe 1 is Continue cooking, saved with [cook]; the clock is the test's virtual time. */
    private fun TestScope.cooking(cook: CookProgress, repository: FakeRecipeRepository = FakeRecipeRepository()): HomeViewModel {
        repository.recent.value = listOf(summary(1), summary(2))
        repository.recipes.value = mapOf(1L to recipe(1, cook))
        val vm = HomeViewModel(repository, clock = Clock { testScheduler.currentTime })
        collectEagerly(vm.uiState)
        return vm
    }

    @Test fun `a recipe that isn't being cooked has no place to resume`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = cooking(CookProgress(currentStep = 2, doneSteps = setOf(0, 1)))
        advanceUntilIdle()

        assertEquals(1L, vm.uiState.value.continueCooking?.id)
        assertNull(vm.uiState.value.resume)
    }

    @Test fun `cook mode left on resumes at its step, with no timer`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = cooking(CookProgress(active = true, currentStep = 1, doneSteps = setOf(0)))
        advanceUntilIdle()

        assertEquals(CookResume(1, step = 2, stepCount = 4, timerSecondsLeft = null), vm.uiState.value.resume)
    }

    @Test fun `a running timer counts down from its deadline, then goes`() = runTest(mainDispatcherRule.dispatcher) {
        val timer = SavedTimer(totalSeconds = 600, remainingSeconds = 600, endsAt = 90_000)
        val repository = FakeRecipeRepository()
        val vm = cooking(CookProgress(active = true, currentStep = 1, timers = mapOf(1 to timer)), repository)
        runCurrent()
        assertEquals(90, vm.uiState.value.resume?.timerSecondsLeft)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(60, vm.uiState.value.resume?.timerSecondsLeft)

        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(CookResume(1, 2, 4, timerSecondsLeft = null), vm.uiState.value.resume)
        assertEquals("nothing is written", emptyList<Any>(), repository.setCookProgressCalls)
    }

    @Test fun `a timer still running out of cook mode is cooking too`() = runTest(mainDispatcherRule.dispatcher) {
        val timer = SavedTimer(totalSeconds = 600, remainingSeconds = 600, endsAt = 120_000)
        val vm = cooking(CookProgress(active = false, currentStep = 3, timers = mapOf(3 to timer)))
        runCurrent()

        assertEquals(CookResume(1, 4, 4, timerSecondsLeft = 120), vm.uiState.value.resume)
    }

    @Test fun `the card follows the cook's progress as it is saved`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = cooking(CookProgress(), repository)
        advanceUntilIdle()
        assertNull(vm.uiState.value.resume)

        repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true, currentStep = 2)))
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.resume?.step)
    }

    @Test fun `a newer recipe never shows the last one's place`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = cooking(CookProgress(active = true), repository)
        advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.resume?.recipeId)

        repository.recent.value = listOf(summary(2), summary(1))
        advanceUntilIdle()

        assertEquals(2L, vm.uiState.value.continueCooking?.id)
        assertNull(vm.uiState.value.resume)
    }


    // #402: a finished run is never a resume, whatever its timers.

    @Test fun `a finished run is never resumed, even with a timer still running`() = runTest(mainDispatcherRule.dispatcher) {
        val timer = SavedTimer(totalSeconds = 600, remainingSeconds = 600, endsAt = 120_000)
        val vm = cooking(CookProgress(active = false, currentStep = 3, doneSteps = setOf(0, 1, 2, 3), timers = mapOf(3 to timer)))
        runCurrent()

        assertEquals(1L, vm.uiState.value.continueCooking?.id)
        assertNull(vm.uiState.value.resume)
    }

    @Test fun `CookResume of a finished run is null whatever its timers and cook mode`() {
        val timer = SavedTimer(600, 600, endsAt = 120_000)
        val finished = recipe(1, CookProgress(active = true, doneSteps = setOf(0, 1, 2, 3), timers = mapOf(3 to timer)))
        assertNull(CookResume.of(finished, now = 0))
        // One step short of finished is still being cooked.
        val almost = recipe(1, CookProgress(active = false, doneSteps = setOf(0, 1, 2), timers = mapOf(3 to timer)))
        assertEquals(120, CookResume.of(almost, now = 0)?.timerSecondsLeft)
    }

    // Today's card (#402): the earliest meal today not yet cooked, from Week.

    private val day0 = 0L // the test clock starts at 0 ms: epoch day 0
    private val plan = FakeMealPlanRepository()
    private val cookedToday = FakeCookedPhotoRepository(today = 0L)

    private fun meal(
        id: Long, type: Long, recipeId: Long?, title: String?, day: Long = day0, note: String? = null, servings: Int? = null
    ) = PlannedMeal(id, day, type, recipeId, title, imageUrl = null, servings = servings, note = note)

    private fun TestScope.home(
        repository: FakeRecipeRepository = FakeRecipeRepository(),
        withCooked: Boolean = true
    ): HomeViewModel {
        val vm = HomeViewModel(
            repository, clock = Clock { testScheduler.currentTime },
            mealPlan = plan, cooked = cookedToday.takeIf { withCooked }
        )
        collectEagerly(vm.uiState)
        return vm
    }

    @Test fun `today's earliest meal leads, the others follow in meal type order`() = runTest(mainDispatcherRule.dispatcher) {
        // Planned out of order, and dinner first in the plan: the types' order decides.
        plan.meals.value = listOf(
            meal(1, FakeMealPlanRepository.DINNER, 12, "Lemon chicken", servings = 6),
            meal(2, FakeMealPlanRepository.SNACK, 13, "Apple slices"),
            meal(3, FakeMealPlanRepository.BREAKFAST, 11, "Porridge"),
            meal(4, FakeMealPlanRepository.LUNCH, 14, "Soup"),
            meal(5, FakeMealPlanRepository.DINNER, 15, "Tomorrow's", day = 1)
        )
        val vm = home()
        runCurrent()

        val card = vm.uiState.value.today!!
        assertEquals("Porridge", card.lead.title)
        assertEquals(11L, card.lead.recipeId)
        assertEquals(listOf("Soup", "Lemon chicken", "Apple slices"), card.others.map { it.title })
        assertEquals(listOf("Lunch", "Dinner", "Snack"), card.others.map { it.mealType })
        assertEquals(6, card.others[1].servings)
    }

    @Test fun `a user's own meal type sits where the cook put it`() = runTest(mainDispatcherRule.dispatcher) {
        plan.types.value = listOf(
            com.example.recipeclipper.data.model.MealType(9, "Brunch", null, 0),
        ) + FakeMealPlanRepository.DEFAULT_TYPES.map { it.copy(sortOrder = it.sortOrder + 1) }
        plan.meals.value = listOf(meal(1, FakeMealPlanRepository.BREAKFAST, 11, "Porridge"), meal(2, 9, 12, "Eggs"))
        val vm = home()
        runCurrent()

        assertEquals("Eggs", vm.uiState.value.today?.lead?.title)
        assertEquals(listOf("Porridge"), vm.uiState.value.today?.others?.map { it.title })
    }

    @Test fun `a meal cooked today drops off, and the earliest one left leads`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(
            meal(1, FakeMealPlanRepository.BREAKFAST, 11, "Porridge"),
            meal(2, FakeMealPlanRepository.LUNCH, 14, "Soup"),
            meal(3, FakeMealPlanRepository.DINNER, 12, "Lemon chicken")
        )
        val vm = home()
        runCurrent()
        assertEquals("Porridge", vm.uiState.value.today?.lead?.title)

        // "Mark as cooked", or a photo, or a finished run: a cooking entry dated today.
        cookedToday.mark(11)
        runCurrent()
        assertEquals("Soup", vm.uiState.value.today?.lead?.title)
        assertEquals(listOf("Lemon chicken"), vm.uiState.value.today?.others?.map { it.title })

        // An entry from another day doesn't count.
        cookedToday.mark(14, day = -1)
        runCurrent()
        assertEquals("Soup", vm.uiState.value.today?.lead?.title)
    }

    @Test fun `every meal cooked today, and nothing mid-cook, is no card`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(
            meal(1, FakeMealPlanRepository.LUNCH, 14, "Soup"),
            meal(2, FakeMealPlanRepository.DINNER, 12, "Lemon chicken")
        )
        val vm = home()
        runCurrent()
        cookedToday.mark(14)
        cookedToday.mark(12)
        runCurrent()

        assertNull(vm.uiState.value.today)
    }

    @Test fun `a note shows its text with no recipe, and a cooked recipe never drops a note`() =
        runTest(mainDispatcherRule.dispatcher) {
            plan.meals.value = listOf(
                meal(1, FakeMealPlanRepository.LUNCH, null, null, note = "Leftovers"),
                meal(2, FakeMealPlanRepository.DINNER, 12, "Lemon chicken")
            )
            val vm = home()
            runCurrent()

            val card = vm.uiState.value.today!!
            assertEquals("Leftovers", card.lead.title)
            assertNull("a note has no Start cooking", card.lead.recipeId)
            assertEquals(listOf("Lemon chicken"), card.others.map { it.title })
        }

    @Test fun `nothing planned today, and nothing mid-cook, is no card`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(meal(1, FakeMealPlanRepository.DINNER, 12, "Tomorrow's", day = 1))
        val repository = FakeRecipeRepository()
        repository.recent.value = listOf(summary(1))
        val vm = home(repository)
        runCurrent()

        assertNull(vm.uiState.value.today)
        assertEquals("the latest recipe still counts for the empty-library check", 1L, vm.uiState.value.continueCooking?.id)
    }

    @Test fun `mid-cook keeps the resume, with today's card beside it for the screen to choose`() =
        runTest(mainDispatcherRule.dispatcher) {
            plan.meals.value = listOf(meal(1, FakeMealPlanRepository.DINNER, 12, "Lemon chicken"))
            val repository = FakeRecipeRepository()
            repository.recent.value = listOf(summary(1))
            repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true, currentStep = 1)))
            val vm = home(repository)
            runCurrent()

            assertEquals(2, vm.uiState.value.resume?.step)
            assertEquals("Lemon chicken", vm.uiState.value.today?.lead?.title)
        }

    @Test fun `the card follows the plan as it changes`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = home()
        runCurrent()
        assertNull(vm.uiState.value.today)

        plan.meals.value = listOf(meal(1, FakeMealPlanRepository.DINNER, 12, "Lemon chicken"))
        runCurrent()
        assertEquals("Lemon chicken", vm.uiState.value.today?.lead?.title)

        plan.meals.value = emptyList()
        runCurrent()
        assertNull(vm.uiState.value.today)
    }

    @Test fun `when the day rolls over the card is the new day's`() = runTest(mainDispatcherRule.dispatcher) {
        plan.meals.value = listOf(
            meal(1, FakeMealPlanRepository.DINNER, 12, "Today's dinner", day = 0),
            meal(2, FakeMealPlanRepository.BREAKFAST, 11, "Tomorrow's porridge", day = 1)
        )
        val vm = home()
        runCurrent()
        assertEquals("Today's dinner", vm.uiState.value.today?.lead?.title)

        advanceTimeBy(24 * 60 * 60 * 1000L)
        runCurrent()
        assertEquals("Tomorrow's porridge", vm.uiState.value.today?.lead?.title)
    }

    @Test fun `without a plan there is no today card`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(FakeRecipeRepository())
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        assertNull(vm.uiState.value.today)
    }
}
