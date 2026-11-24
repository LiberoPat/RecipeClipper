package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.LUNCH
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Plan's quiet pantry note (#456), through the screen: a planned recipe the pantry lacks two
 * ingredients of says so in one muted line; one with everything says nothing. A tap opens the note
 * (every name, and "Add to groceries"), without opening the recipe; adding offers one Undo, and the
 * note then says "On your grocery list". The ViewModel's side is `WeekPantryNoteTest`. iOS:
 * `WeekPantryNoteUITests`.
 */
@RunWith(AndroidJUnit4::class)
class WeekPantryNoteScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = FakePlanCalendar.WEDNESDAY
    private val plan = FakeMealPlanRepository()
    private val groceries = FakeGroceryRepository()
    private val pantry = FakePantryRepository(
        listOf("flour", "chickpeas", "eggs", "milk").mapIndexed { i, name ->
            PantryItem(i + 1L, name, null, "en", Aisle.OTHER, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = null)
        }
    )
    private var opened = 0

    private fun planRecipe(recipeId: Long, title: String, type: Long, vararg lines: String) {
        plan.titles[recipeId] = title
        plan.lines[recipeId] = lines.toList()
        runBlocking { plan.addRecipe(recipeId, today, type, null) }
        val id = plan.meals.value.last().id
        groceries.planned = groceries.planned + PlannedIngredients(id, today, null, recipeId, title, lines.toList(), null, "en")
    }

    private fun planDay() {
        planRecipe(7, "Falafel", LUNCH, "1 can chickpeas", "2 tbsp tahini", "1 tsp sumac", "2 tbsp flour")
        planRecipe(8, "Pancakes", DINNER, "For the batter:", "2 cups flour", "2 eggs", "1 cup milk")
    }

    private fun show(on: Boolean = true) {
        val flags = FeatureFlags(FakeFeatureFlagStore(mapOf("planPantryNote" to on)), FlagRegistry.definitions, isDebug = true)
        val vm = WeekViewModel(
            plan, FakeRecipeRepository(), FakePlanCalendar(), groceries = groceries, flags = flags, pantry = pantry,
            preferences = FakeAppPreferences()
        )
        compose.setContent { WeekScreen(onOpenRecipe = { _, _ -> opened++ }, onOpenMealTypes = {}, viewModel = vm) }
    }

    @Test
    fun theMissingIngredientsShowUnderTheMealAndNothingUnderOneWithEverything() {
        planDay()
        show()
        val (falafel, pancakes) = plan.meals.value

        compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag("meal-${pancakes.id}"))
        compose.onNodeWithText("Not in your pantry: tahini, sumac").assertIsDisplayed()
        compose.onNodeWithTag("pantryGap-${falafel.id}").assertIsDisplayed()
        compose.onNodeWithTag("pantryGap-${pancakes.id}").assertDoesNotExist()
    }

    @Test
    fun aTapOpensTheNoteAddsToGroceriesWithOneUndoAndTheNoteThenSaysSo() {
        planDay()
        show()
        val falafel = plan.meals.value[0]
        compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag("meal-${falafel.id}"))

        // Its own tap target, named for what it does: the recipe doesn't open.
        compose.onNodeWithTag("pantryGap-${falafel.id}")
            .assert(SemanticsMatcher("labelled") { SemanticsActions.OnClick in it.config && it.config[SemanticsActions.OnClick].label == "Show what's missing" })
            .performClick()
        assertEquals(0, opened)
        compose.onNodeWithTag("pantryGapAdd-${falafel.id}").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(listOf("2 tbsp tahini", "1 tsp sumac"), groceries.items.value.map { it.text })
        compose.onNodeWithText("Added 2 to groceries").assertIsDisplayed()
        compose.onNodeWithText("On your grocery list").assertIsDisplayed()
        compose.onNodeWithTag("pantryGap-${falafel.id}", useUnmergedTree = true).assertHasNoClickAction()

        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), groceries.items.value.map { it.text })
        compose.onNodeWithText("Not in your pantry: tahini, sumac").assertIsDisplayed()
    }

    @Test
    fun pastThreeNamesItSaysHowManyMoreAndATapShowsThemAll() {
        planRecipe(7, "Mezze", LUNCH, "2 tbsp tahini", "1 tsp sumac", "1 lemon", "2 cloves garlic", "1 bunch parsley")
        show()
        val mezze = plan.meals.value[0]
        compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag("meal-${mezze.id}"))
        compose.onNodeWithText("Not in your pantry: tahini, sumac and 3 more").assertIsDisplayed().performClick()
        compose.onNodeWithText("Not in your pantry: tahini, sumac, lemon, garlic, parsley").assertIsDisplayed()
        compose.onNodeWithTag("pantryGapAdd-${mezze.id}").assertIsDisplayed()

        // Again: closed.
        compose.onNodeWithTag("pantryGap-${mezze.id}").performClick()
        compose.onNodeWithText("Not in your pantry: tahini, sumac and 3 more").assertIsDisplayed()
        compose.onNodeWithTag("pantryGapAdd-${mezze.id}").assertDoesNotExist()
        assertEquals(0, opened)
    }

    @Test
    fun theThumbnailStaysWhereItIsWhenTheNoteOpensAndCloses() {
        // The capped note grows the most (every name), the other only gains "Add to groceries".
        planRecipe(7, "Mezze", LUNCH, "2 tbsp tahini", "1 tsp sumac", "1 lemon", "2 cloves garlic", "1 bunch parsley")
        planRecipe(8, "Falafel", DINNER, "1 can chickpeas", "2 tbsp tahini", "1 tsp sumac", "2 tbsp flour")
        show()
        for (meal in plan.meals.value) {
            compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag("meal-${meal.id}"))
            val closed = compose.onNodeWithTag("mealPhoto-${meal.id}", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val noteClosed = compose.onNodeWithTag("pantryGap-${meal.id}").getUnclippedBoundsInRoot()

            compose.onNodeWithTag("pantryGap-${meal.id}").performClick()
            compose.onNodeWithTag("pantryGapAdd-${meal.id}").assertIsDisplayed()
            val open = compose.onNodeWithTag("mealPhoto-${meal.id}", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val noteOpen = compose.onNodeWithTag("pantryGap-${meal.id}").getUnclippedBoundsInRoot()
            // The note's own line, which does grow, starts where it did; the photo doesn't move.
            assertEquals(noteClosed.top, noteOpen.top)
            assertEquals(closed.top, open.top)
            assertEquals(closed.left, open.left)

            compose.onNodeWithTag("pantryGap-${meal.id}").performClick()
            compose.onNodeWithTag("pantryGapAdd-${meal.id}").assertDoesNotExist()
            assertEquals(closed.top, compose.onNodeWithTag("mealPhoto-${meal.id}", useUnmergedTree = true).getUnclippedBoundsInRoot().top)
        }
    }

    @Test
    fun withTheFlagOffThereIsNoNote() {
        planDay()
        show(on = false)
        compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag("meal-${plan.meals.value[1].id}"))
        compose.onNodeWithText("Not in your pantry", substring = true).assertDoesNotExist()
    }
}
