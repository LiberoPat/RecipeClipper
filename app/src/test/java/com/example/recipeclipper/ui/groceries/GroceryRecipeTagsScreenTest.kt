package com.example.recipeclipper.ui.groceries

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagValues
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.ui.common.LocalFlagValues
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A grocery line's recipe as a small tag (#422), a real GroceriesViewModel over the fakes.
 * iOS: `GroceryRecipeTagsTests` and `GroceriesUITests`.
 */
@RunWith(AndroidJUnit4::class)
class GroceryRecipeTagsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeGroceryRepository()
    private val plan = FakeMealPlanRepository()
    private val wednesday = FakePlanCalendar.WEDNESDAY
    private val opened = mutableListOf<Pair<Long, Int?>>()

    private fun show(tags: Boolean = true) {
        val viewModel = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar(), null, plan)
        compose.setContent {
            CompositionLocalProvider(LocalFlagValues provides FlagValues(if (tags) setOf(Flag.GROCERY_RECIPE_TAGS) else emptySet())) {
                GroceriesScreen(viewModel = viewModel, onOpenRecipe = { id, servings -> opened += id to servings })
            }
        }
    }

    private fun add(vararg lines: NewGroceryLine) = runBlocking { repository.add(lines.toList()) }

    @Test
    fun aPlannedRecipeIsATagWithItsDayApartFromTheAmount() {
        repository.recipeTitles.value = mapOf(1L to "Mushroom omelette")
        add(NewGroceryLine("200 g mushrooms, sliced", "en", recipeId = 1, plannedDay = wednesday))
        show()

        // The amount and note stay plain text; the recipe is its own tag, not a muted line.
        compose.onNodeWithText("200 g mushrooms, sliced").assertIsDisplayed()
        compose.onNodeWithText("Mushroom omelette · Wed").assertIsDisplayed()
        // A button that reads "For <recipe>, <weekday>".
        compose.onNodeWithContentDescription("For Mushroom omelette, Wednesday")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun aRecipeNotPlannedHasNoDay() {
        repository.recipeTitles.value = mapOf(1L to "Chicken Adobo")
        add(NewGroceryLine("2 limes", "en", recipeId = 1))
        show()

        compose.onNodeWithText("Chicken Adobo").assertIsDisplayed()
        compose.onNodeWithContentDescription("For Chicken Adobo").assertIsDisplayed()
    }

    @Test
    fun aTypedItemHasNoTag() {
        add(NewGroceryLine("milk", "en"))
        show()

        compose.onNodeWithText("milk").assertIsDisplayed()
        compose.onNodeWithContentDescription("For", substring = true).assertDoesNotExist()
    }

    @Test
    fun aRecipeThatIsGoneLeavesNoTag() {
        add(NewGroceryLine("2 limes", "en", recipeId = 99))
        show()

        compose.onNodeWithText("2 limes").assertIsDisplayed()
        compose.onNodeWithContentDescription("For", substring = true).assertDoesNotExist()
    }

    @Test
    fun anAddedUpRowKeepsItsSumAndGetsATagPerRecipe() {
        repository.recipeTitles.value = mapOf(1L to "Chicken Adobo", 2L to "Weeknight Chili")
        add(
            NewGroceryLine("200 g flour", "en", recipeId = 1),
            NewGroceryLine("100 g flour", "en", recipeId = 2)
        )
        show()

        compose.onNodeWithText("300 g flour").assertIsDisplayed()
        compose.onNodeWithText("200 g flour + 100 g flour").assertIsDisplayed()
        compose.onNodeWithContentDescription("For Chicken Adobo").assertIsDisplayed()
        compose.onNodeWithContentDescription("For Weeknight Chili").assertIsDisplayed()
        // Not the old comma line.
        compose.onNodeWithText("Chicken Adobo, Weeknight Chili").assertDoesNotExist()
    }

    @Test
    fun threeRecipesShowWholeAndMoreThanThreeShowTwoAndPlusN() {
        repository.recipeTitles.value = mapOf(1L to "Soup", 2L to "Stew", 3L to "Curry", 4L to "Pasta", 5L to "Tacos")
        add(
            NewGroceryLine("1 onion", "en", recipeId = 1),
            NewGroceryLine("1 onion", "en", recipeId = 2),
            NewGroceryLine("1 onion", "en", recipeId = 3)
        )
        add(NewGroceryLine("1 leek", "en", recipeId = 1), NewGroceryLine("1 leek", "en", recipeId = 2),
            NewGroceryLine("1 leek", "en", recipeId = 3), NewGroceryLine("1 leek", "en", recipeId = 4),
            NewGroceryLine("1 leek", "en", recipeId = 5))
        show()

        // Three onions' recipes all show, no "+N"; the leek's five show the first two and "+3".
        compose.onNodeWithContentDescription("For Curry").assertIsDisplayed()
        compose.onNodeWithContentDescription("For Pasta").assertDoesNotExist()
        compose.onNodeWithContentDescription("For Tacos").assertDoesNotExist()
        // The "+3" is a quiet label the row reads as "3 more recipes", not a button.
        val more = compose.onNodeWithTag("groceryTag-grocery-4-more", useUnmergedTree = true)
        more.assertIsDisplayed().assertContentDescriptionEquals("3 more recipes")
        compose.onNodeWithTag("groceryTag-grocery-1-more", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun tappingATagOpensThatRecipeAtItsPlannedServings() {
        repository.recipeTitles.value = mapOf(1L to "Mushroom omelette")
        add(NewGroceryLine("200 g mushrooms", "en", recipeId = 1, plannedDay = wednesday))
        runBlocking { plan.addRecipe(1, wednesday, 1, 6) }
        show()

        compose.onNodeWithContentDescription("For Mushroom omelette, Wednesday").performClick()
        compose.runOnIdle { assertEquals(listOf(1L to 6), opened) }
    }

    @Test
    fun aTagOfARecipeNotPlannedOpensItAtItsOwnYield() {
        repository.recipeTitles.value = mapOf(1L to "Chicken Adobo")
        add(NewGroceryLine("2 limes", "en", recipeId = 1))
        show()

        compose.onNodeWithContentDescription("For Chicken Adobo").performClick()
        compose.runOnIdle { assertEquals(listOf<Pair<Long, Int?>>(1L to null), opened) }
    }

    @Test
    fun theRowStillTicksAndTheTagNeverTicksIt() {
        repository.recipeTitles.value = mapOf(1L to "Chicken Adobo")
        add(NewGroceryLine("2 limes", "en", recipeId = 1))
        show()
        val id = repository.items.value.single().id

        // The tag opens the recipe and leaves the tick alone.
        compose.onNodeWithContentDescription("For Chicken Adobo").performClick()
        compose.onNodeWithTag("grocery-$id").assertIsOff()
        // The row is still the checkbox, with its toggle state.
        compose.onNodeWithTag("grocery-$id")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
        compose.onNodeWithTag("grocery-$id").assertIsOn()
        compose.runOnIdle { assertEquals(1, opened.size) }
    }

    @Test
    fun withTheFlagOffTheRecipesAreTheMutedLineAsBefore() {
        repository.recipeTitles.value = mapOf(1L to "Chicken Adobo", 2L to "Weeknight Chili")
        add(
            NewGroceryLine("200 g flour", "en", recipeId = 1, plannedDay = wednesday),
            NewGroceryLine("100 g flour", "en", recipeId = 2)
        )
        show(tags = false)

        compose.onNodeWithText("Chicken Adobo, Weeknight Chili").assertIsDisplayed()
        compose.onNodeWithContentDescription("For Chicken Adobo").assertDoesNotExist()
    }
}
