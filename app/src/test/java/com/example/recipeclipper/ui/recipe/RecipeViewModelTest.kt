package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.IngredientScaler
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeShareText
import com.example.recipeclipper.data.model.Servings
import com.example.recipeclipper.data.model.TemperatureConverter
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitConverter
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
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
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun byId(id: Long) = SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to id))
    private fun byUrl(url: String) = SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to url))
    private fun noArgs() = SavedStateHandle()

    private fun testRecipe(
        id: Long = 1L,
        yield: String? = "4 servings",
        ingredients: List<String> = listOf("2 cups flour", "1 cup milk"),
        instructions: List<String> = listOf(
            "Preheat the oven to 350°F.",
            "Mix for 5 minutes.",
            "Bake for 10 minutes.",
            "Cool completely."
        ),
        checkedIngredients: Set<Int> = emptySet(),
        notes: String? = null
    ) = Recipe(
        name = "Test Recipe",
        image = null,
        ingredients = ingredients,
        instructions = instructions,
        prepTime = "10m",
        cookTime = "20m",
        totalTime = "30m",
        yield = yield,
        sourceUrl = "https://example.com/recipe",
        id = id,
        checkedIngredients = checkedIngredients,
        notes = notes
    )

    // The [Clock] reads the same virtual clock the test's own `advanceTimeBy`/`advanceUntilIdle`
    // drive, so wall-clock timer deadlines line up with the dispatcher's virtual time.
    private fun TestScope.buildViewModel(
        savedStateHandle: SavedStateHandle,
        repository: FakeRecipeRepository,
        unitPreferences: FakeAppPreferences = FakeAppPreferences(),
        connectivity: FakeConnectivity = FakeConnectivity()
    ): RecipeViewModel = RecipeViewModel(
        savedStateHandle, repository, unitPreferences, Clock { testScheduler.currentTime }, connectivity
    )

    // --- Loading ---

    @Test fun `loads a recipe by id`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        val content = vm.uiState.value.content
        assertTrue(content is RecipeContent.Success)
        assertEquals("Test Recipe", (content as RecipeContent.Success).recipe.name)
    }

    @Test fun `loads a recipe by url`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            importResult = ParseResult.Success(testRecipe())
        }
        val vm = buildViewModel(byUrl("https://example.com/recipe"), repository)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.content is RecipeContent.Success)
    }

    @Test fun `errors when neither id nor url is present`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(noArgs(), FakeRecipeRepository())
        advanceUntilIdle()

        val content = vm.uiState.value.content as RecipeContent.Error
        assertEquals(ParseError.NothingToShow, content.error)
    }

    @Test fun `errors when open returns null`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = null }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        val content = vm.uiState.value.content as RecipeContent.Error
        assertEquals(ParseError.NotSaved, content.error)
    }

    @Test fun `every import error kind reaches the screen state as its cause`() =
        runTest(mainDispatcherRule.dispatcher) {
            val kinds = listOf(
                ParseError.Blocked(403),
                ParseError.Offline,
                ParseError.FetchFailed("HTTP 400"),
                ParseError.FetchFailed("timeout", timedOut = true),
                ParseError.NoRecipeFound,
                ParseError.SaveFailed
            )
            for (kind in kinds) {
                val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(kind) }
                val vm = buildViewModel(byUrl("https://example.com/recipe"), repository)
                advanceUntilIdle()
                assertEquals(kind, (vm.uiState.value.content as RecipeContent.Error).error)
            }
        }

    @Test fun `offline then back online reloads exactly once`() = runTest(mainDispatcherRule.dispatcher) {
        val connectivity = FakeConnectivity(online = false)
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(ParseError.Offline) }
        val vm = buildViewModel(byUrl("https://example.com/recipe"), repository, connectivity = connectivity)
        advanceUntilIdle()
        assertEquals(1, repository.importCalls)

        repository.importResult = ParseResult.Success(testRecipe())
        connectivity.state.value = true
        advanceUntilIdle()

        assertEquals(2, repository.importCalls)
        assertTrue(vm.uiState.value.content is RecipeContent.Success)

        // Showing a recipe now: later drops and returns change nothing.
        connectivity.state.value = false
        advanceUntilIdle()
        connectivity.state.value = true
        advanceUntilIdle()
        assertEquals(2, repository.importCalls)
    }

    @Test fun `a FetchFailed while online waits for a real drop and return before reloading`() =
        runTest(mainDispatcherRule.dispatcher) {
            val connectivity = FakeConnectivity(online = true)
            val repository = FakeRecipeRepository().apply {
                importResult = ParseResult.Error(ParseError.FetchFailed("reset"))
            }
            buildViewModel(byUrl("https://example.com/recipe"), repository, connectivity = connectivity)
            advanceUntilIdle()
            assertEquals(1, repository.importCalls) // online already: not a transition

            connectivity.state.value = false
            advanceUntilIdle()
            assertEquals(1, repository.importCalls)
            connectivity.state.value = true
            advanceUntilIdle()
            assertEquals(2, repository.importCalls)
        }

    @Test fun `errors that reconnecting can't fix don't reload`() = runTest(mainDispatcherRule.dispatcher) {
        for (kind in listOf(ParseError.Blocked(403), ParseError.NoRecipeFound)) {
            val connectivity = FakeConnectivity(online = false)
            val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(kind) }
            buildViewModel(byUrl("https://example.com/recipe"), repository, connectivity = connectivity)
            advanceUntilIdle()
            connectivity.state.value = true
            advanceUntilIdle()
            assertEquals("$kind", 1, repository.importCalls)
        }
    }

    @Test fun `Try again while offline doesn't leave a second reconnect watcher behind`() =
        runTest(mainDispatcherRule.dispatcher) {
            val connectivity = FakeConnectivity(online = false)
            val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(ParseError.Offline) }
            val vm = buildViewModel(byUrl("https://example.com/recipe"), repository, connectivity = connectivity)
            advanceUntilIdle()
            vm.onRetry()
            advanceUntilIdle()
            assertEquals(2, repository.importCalls)

            repository.importResult = ParseResult.Success(testRecipe())
            connectivity.state.value = true
            advanceUntilIdle()
            assertEquals(3, repository.importCalls)
        }

    @Test fun `checkedIngredients are seeded from the loaded recipe`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = testRecipe(checkedIngredients = setOf(1))
        }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        assertEquals(setOf(1), vm.uiState.value.checkedIngredients)
    }

    @Test fun `onIngredientChecked updates state and persists through the repository`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { openResult = testRecipe(id = 5L) }
            val vm = buildViewModel(byId(5L), repository)
            advanceUntilIdle()

            vm.onIngredientChecked(0, true)
            advanceUntilIdle()

            assertEquals(setOf(0), vm.uiState.value.checkedIngredients)
            assertEquals(listOf(5L to setOf(0)), repository.setCheckedCalls)
        }

    // --- Notes ---

    @Test fun `the note is seeded from the loaded recipe`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe(notes = "Half the sugar") }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        assertEquals("Half the sugar", vm.uiState.value.notes)
    }

    @Test fun `a recipe without a note shows an empty one`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe(notes = null) }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.notes)
    }

    @Test fun `typing shows at once and saves once, after the pause`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { openResult = testRecipe(id = 5L) }
            val vm = buildViewModel(byId(5L), repository)
            advanceUntilIdle()

            vm.onNotesChange("N")
            vm.onNotesChange("Ne")
            advanceTimeBy(RecipeViewModel.NOTES_SAVE_DELAY_MS - 1)
            vm.onNotesChange("Needs 10 more minutes")

            assertEquals("Needs 10 more minutes", vm.uiState.value.notes)
            assertEquals(emptyList<Pair<Long, String>>(), repository.setNotesCalls)

            advanceTimeBy(RecipeViewModel.NOTES_SAVE_DELAY_MS + 1)
            assertEquals(listOf(5L to "Needs 10 more minutes"), repository.setNotesCalls)
        }

    @Test fun `clearing the note saves the empty text`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe(id = 5L, notes = "Old") }
        val vm = buildViewModel(byId(5L), repository)
        advanceUntilIdle()

        vm.onNotesChange("")
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.notes)
        assertEquals(listOf(5L to ""), repository.setNotesCalls)
    }

    @Test fun `leaving the screen mid-pause still saves the note`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe(id = 5L) }
        val store = ViewModelStore()
        val vm = buildViewModel(byId(5L), repository)
        store.put("recipe", vm)
        advanceUntilIdle()

        vm.onNotesChange("Less salt")
        store.clear() // what leaving the screen does; viewModelScope is cancelled first
        advanceUntilIdle()

        assertEquals(listOf(5L to "Less salt"), repository.setNotesCalls)
    }

    @Test fun `a note saved by the pause is not written again on leaving`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { openResult = testRecipe(id = 5L) }
            val store = ViewModelStore()
            val vm = buildViewModel(byId(5L), repository)
            store.put("recipe", vm)
            advanceUntilIdle()

            vm.onNotesChange("Less salt")
            advanceUntilIdle()
            store.clear()
            advanceUntilIdle()

            assertEquals(listOf(5L to "Less salt"), repository.setNotesCalls)
        }

    @Test fun `no note is written before the recipe has loaded`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe(id = 5L) }
        val vm = buildViewModel(byId(5L), repository)

        vm.onNotesChange("Too early")
        advanceUntilIdle()

        assertEquals(emptyList<Pair<Long, String>>(), repository.setNotesCalls)
    }

    // --- Servings ---

    @Test fun `changing servings rescales the ingredient list`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onServingsChange(8) // base 4 -> target 8, factor 2.0
        advanceUntilIdle()

        val content = vm.uiState.value.content as RecipeContent.Success
        val expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale(it, 2.0), UnitSystem.AS_WRITTEN, false)
        }
        assertEquals(expected, content.ingredients)
        assertEquals(8, content.servings?.target)
    }

    @Test fun `servings are clamped to 1 and to Servings MAX`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onServingsChange(0)
        advanceUntilIdle()
        assertEquals(1, (vm.uiState.value.content as RecipeContent.Success).servings?.target)

        vm.onServingsChange(1000)
        advanceUntilIdle()
        assertEquals(Servings.MAX, (vm.uiState.value.content as RecipeContent.Success).servings?.target)
    }

    @Test fun `changing servings is a no-op when the recipe has no usable yield`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { openResult = testRecipe(yield = "a lot") }
            val vm = buildViewModel(byId(1L), repository)
            advanceUntilIdle()

            val before = vm.uiState.value
            vm.onServingsChange(10)
            advanceUntilIdle()

            assertEquals(before, vm.uiState.value)
            assertNull((vm.uiState.value.content as RecipeContent.Success).servings)
        }

    // --- Units ---

    @Test fun `changing units re-renders ingredients and instructions and writes through preferences`() =
        runTest(mainDispatcherRule.dispatcher) {
            val unitPreferences = FakeAppPreferences()
            val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
            val vm = buildViewModel(byId(1L), repository, unitPreferences)
            advanceUntilIdle()

            vm.onUnitSystemChange(UnitSystem.GRAMS)
            advanceUntilIdle()

            val content = vm.uiState.value.content as RecipeContent.Success
            val expectedIngredients = testRecipe().ingredients.map {
                UnitConverter.convert(IngredientScaler.scale(it, 1.0), UnitSystem.GRAMS, false)
            }
            // Oven temperature is decoupled from the unit system (defaults AS_WRITTEN), so
            // changing UnitSystem alone must leave instructions untouched.
            val expectedInstructions = testRecipe().instructions.map {
                TemperatureConverter.convert(it, TemperatureUnit.AS_WRITTEN)
            }
            assertEquals(expectedIngredients, content.ingredients)
            assertEquals(expectedInstructions, content.instructions)
            assertEquals(UnitSystem.GRAMS, unitPreferences.unitSystem)
        }

    @Test fun `toggling convert liquids re-renders ingredients and writes through preferences`() =
        runTest(mainDispatcherRule.dispatcher) {
            val unitPreferences = FakeAppPreferences()
            val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
            val vm = buildViewModel(byId(1L), repository, unitPreferences)
            advanceUntilIdle()
            vm.onUnitSystemChange(UnitSystem.GRAMS)
            advanceUntilIdle()

            vm.onConvertLiquidsChange(true)
            advanceUntilIdle()

            val content = vm.uiState.value.content as RecipeContent.Success
            val expectedIngredients = testRecipe().ingredients.map {
                UnitConverter.convert(IngredientScaler.scale(it, 1.0), UnitSystem.GRAMS, true)
            }
            assertEquals(expectedIngredients, content.ingredients)
            assertTrue(unitPreferences.convertLiquids)
        }

    @Test fun `dark while cooking is off by default and writes through when turned on`() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeAppPreferences()
            val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
            val vm = buildViewModel(byId(1L), repository, preferences)
            advanceUntilIdle()

            // Off by default: cook mode follows the system theme like every other screen.
            assertFalse(vm.uiState.value.darkWhileCooking)

            vm.onDarkWhileCookingChange(true)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.darkWhileCooking)
            assertTrue(preferences.darkWhileCooking)
        }

    @Test fun `dark while cooking is seeded from preferences and leaves the recipe text alone`() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeAppPreferences(darkWhileCooking = true)
            val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
            val vm = buildViewModel(byId(1L), repository, preferences)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.darkWhileCooking)

            val before = (vm.uiState.value.content as RecipeContent.Success).ingredients
            vm.onDarkWhileCookingChange(false)
            advanceUntilIdle()

            // A display choice, so nothing about the rendered recipe may change.
            val after = (vm.uiState.value.content as RecipeContent.Success).ingredients
            assertEquals(before, after)
        }

    @Test fun `temperature unit is seeded from preferences and converts instructions`() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeAppPreferences(temperatureUnit = TemperatureUnit.CELSIUS)
            val repository = FakeRecipeRepository().apply {
                openResult = testRecipe(instructions = listOf("Bake at 350°F"))
            }
            val vm = buildViewModel(byId(1L), repository, preferences)
            advanceUntilIdle()

            assertEquals(TemperatureUnit.CELSIUS, vm.uiState.value.temperatureUnit)
            val content = vm.uiState.value.content as RecipeContent.Success
            assertEquals(listOf("Bake at 180°C"), content.instructions)
        }

    @Test fun `default temperature unit is as written and leaves instructions untouched`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply {
                openResult = testRecipe(instructions = listOf("Bake at 350°F"))
            }
            val vm = buildViewModel(byId(1L), repository)
            advanceUntilIdle()

            assertEquals(TemperatureUnit.AS_WRITTEN, vm.uiState.value.temperatureUnit)
            val content = vm.uiState.value.content as RecipeContent.Success
            assertEquals(listOf("Bake at 350°F"), content.instructions)
        }

    @Test fun `scaling and converting together matches the scale-then-convert order`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
            val vm = buildViewModel(byId(1L), repository)
            advanceUntilIdle()

            vm.onServingsChange(2) // base 4 -> target 2, factor 0.5
            advanceUntilIdle()
            vm.onUnitSystemChange(UnitSystem.METRIC)
            advanceUntilIdle()

            val content = vm.uiState.value.content as RecipeContent.Success
            // The documented ordering rule: scale first, then convert. Computing "expected"
            // any other order (convert then scale) would not match if the ViewModel regressed.
            val expected = testRecipe().ingredients.map {
                UnitConverter.convert(IngredientScaler.scale(it, 0.5), UnitSystem.METRIC, false)
            }
            assertEquals(expected, content.ingredients)
        }

    // --- Cook mode ---

    @Test fun `onCookStart is a no-op with no instructions`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = testRecipe(instructions = emptyList())
        }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onCookStart()
        advanceUntilIdle()

        assertEquals(CookState(), vm.uiState.value.cook)
    }

    @Test fun `a finished cook run starts fresh`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onCookStart()
        advanceUntilIdle()
        repeat(4) {
            vm.onStepDone()
            advanceUntilIdle()
        }
        assertFalse(vm.uiState.value.cook.active)
        assertEquals(setOf(0, 1, 2, 3), vm.uiState.value.cook.doneSteps)

        vm.onCookStart()
        advanceUntilIdle()

        assertEquals(CookState(active = true, currentStep = 0), vm.uiState.value.cook)
    }

    @Test fun `an unfinished cook run resumes where it left off`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onCookStart()
        advanceUntilIdle()
        vm.onStepDone() // done = {0}, currentStep -> 1
        advanceUntilIdle()
        vm.onCookExit()
        advanceUntilIdle()

        vm.onCookStart()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.cook.active)
        assertEquals(1, vm.uiState.value.cook.currentStep)
        assertEquals(setOf(0), vm.uiState.value.cook.doneSteps)
    }

    @Test fun `onStepDone advances, wraps to the earliest skipped step, and deactivates when all done`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
            val vm = buildViewModel(byId(1L), repository)
            advanceUntilIdle()
            vm.onCookStart()
            advanceUntilIdle()

            vm.onStepDone() // step 0 done -> currentStep = 1
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.cook.currentStep)
            assertEquals(setOf(0), vm.uiState.value.cook.doneSteps)

            vm.onStepSelected(3) // forgiving jump ahead, skipping 1 and 2; doesn't touch doneSteps
            advanceUntilIdle()
            assertEquals(3, vm.uiState.value.cook.currentStep)
            assertEquals(setOf(0), vm.uiState.value.cook.doneSteps)

            vm.onStepDone() // step 3 (the last) done -> no unfinished step after it -> wrap to 1
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.cook.currentStep)
            assertEquals(setOf(0, 3), vm.uiState.value.cook.doneSteps)
            assertTrue(vm.uiState.value.cook.active)

            vm.onStepDone() // step 1 done -> next unfinished is 2
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.cook.currentStep)
            assertEquals(setOf(0, 1, 3), vm.uiState.value.cook.doneSteps)

            vm.onStepDone() // step 2 done -> everything done -> deactivate
            advanceUntilIdle()
            assertFalse(vm.uiState.value.cook.active)
            assertEquals(setOf(0, 1, 2, 3), vm.uiState.value.cook.doneSteps)
        }

    // --- Timers ---

    @Test fun `starting a timer sets total and remaining seconds`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = testRecipe(instructions = listOf("Wait for 5 seconds."))
        }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        // No further advancing: the initial timer state is set synchronously inside
        // onTimerStart, before the ticking coroutine (which advanceUntilIdle would run to
        // completion, since this bounded 5-second countdown has nothing else to wait on).
        vm.onTimerStart(0)

        val timer = vm.uiState.value.cook.timers.getValue(0)
        assertEquals(5, timer.totalSeconds)
        assertEquals(5, timer.remainingSeconds)
        assertTrue(timer.running)
    }

    @Test fun `a running timer decrements as virtual time passes`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = testRecipe(instructions = listOf("Wait for 5 seconds."))
        }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onTimerStart(0)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(4, vm.uiState.value.cook.timers.getValue(0).remainingSeconds)
    }

    @Test fun `a timer reaching zero is marked finished`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = testRecipe(instructions = listOf("Wait for 5 seconds."))
        }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onTimerStart(0)
        advanceUntilIdle() // the ticking loop naturally terminates once the timer empties

        val timer = vm.uiState.value.cook.timers.getValue(0)
        assertEquals(0, timer.remainingSeconds)
        assertFalse(timer.running)
        assertTrue(timer.finished)
    }

    @Test fun `pausing and resuming a timer`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = testRecipe(instructions = listOf("Wait for 5 seconds."))
        }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onTimerStart(0)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(4, vm.uiState.value.cook.timers.getValue(0).remainingSeconds)

        vm.onTimerToggle(0) // pause
        advanceUntilIdle()
        val paused = vm.uiState.value.cook.timers.getValue(0)
        assertFalse(paused.running)
        assertEquals(4, paused.remainingSeconds)

        // Time passing while paused changes nothing.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(4, vm.uiState.value.cook.timers.getValue(0).remainingSeconds)

        vm.onTimerToggle(0) // resume
        advanceTimeBy(1_000)
        runCurrent()
        val resumed = vm.uiState.value.cook.timers.getValue(0)
        assertEquals(3, resumed.remainingSeconds)
        assertTrue(resumed.running)
    }

    @Test fun `resetting a timer restores the total as remaining and stops it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply {
                openResult = testRecipe(instructions = listOf("Wait for 5 seconds."))
            }
            val vm = buildViewModel(byId(1L), repository)
            advanceUntilIdle()

            vm.onTimerStart(0)
            advanceTimeBy(2_000)
            runCurrent()
            vm.onTimerReset(0)
            advanceUntilIdle()

            val timer = vm.uiState.value.cook.timers.getValue(0)
            assertEquals(5, timer.remainingSeconds)
            assertFalse(timer.running)
        }

    @Test fun `onTimerAlerted marks the timer alerted`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = testRecipe(instructions = listOf("Wait for 5 seconds."))
        }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onTimerStart(0)
        advanceUntilIdle() // runs to completion
        vm.onTimerAlerted(0)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.cook.timers.getValue(0).alerted)
    }

    @Test fun `two timers run at once, independently`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = testRecipe(instructions = listOf("Wait for 5 seconds.", "Wait for 10 seconds."))
        }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onTimerStart(0)
        vm.onTimerStart(1)
        advanceTimeBy(1_000)
        runCurrent()

        val ticking = vm.uiState.value.cook.timers
        assertEquals(4, ticking.getValue(0).remainingSeconds)
        assertEquals(9, ticking.getValue(1).remainingSeconds)
        assertTrue(ticking.getValue(0).running)
        assertTrue(ticking.getValue(1).running)

        advanceTimeBy(4_000) // total 5s elapsed: timer 0 (5s) finishes, timer 1 (10s) keeps going
        runCurrent()

        val after = vm.uiState.value.cook.timers
        assertTrue(after.getValue(0).finished)
        assertEquals(5, after.getValue(1).remainingSeconds)
        assertTrue(after.getValue(1).running)
    }

    // --- Sharing ---

    @Test fun `shareText reflects the current scaling and units`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = testRecipe() }
        val vm = buildViewModel(byId(1L), repository)
        advanceUntilIdle()

        vm.onServingsChange(8)
        advanceUntilIdle()
        vm.onUnitSystemChange(UnitSystem.METRIC)
        advanceUntilIdle()

        val content = vm.uiState.value.content as RecipeContent.Success
        val expected = RecipeShareText.format(
            recipe = content.recipe,
            servings = content.servings,
            ingredients = content.ingredients,
            instructions = content.instructions
        )
        assertEquals(expected, vm.shareText())
    }
}
