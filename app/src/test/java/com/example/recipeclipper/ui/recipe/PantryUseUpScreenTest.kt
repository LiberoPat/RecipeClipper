package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finishing cook mode with ingredients ticked (#147) opens "Update the pantry" over the reading
 * view: the worked-out change ticked and read aloud without the arrow, the one that can't be
 * worked out as keep / running low / out, one button, one Undo.
 */
@RunWith(AndroidJUnit4::class)
class PantryUseUpScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(id: Long, name: String, quantity: String?) =
        PantryItem(id, name, quantity, "en", Aisle.OTHER, inStock = true, alwaysHave = false, purchasedDay = 1, expiresDay = null)

    private val milk = item(1, "milk", "4 cups")
    private val flour = item(2, "flour", null)
    private val pantry = FakePantryRepository(listOf(milk, flour))
    private val groceries = FakeGroceryRepository()

    // "2 cups flour" and "1 cup milk", both ticked; four steps.
    private val fixture = RecipeScreenFixture(
        recipe = RecipeScreenFixture.testRecipe().copy(checkedIngredients = setOf(0, 1)),
        groceries = groceries,
        pantry = pantry
    )

    private fun cookToTheEnd() {
        fixture.show(compose)
        compose.onNodeWithText("Start cooking").performClick()
        repeat(3) { compose.onNodeWithText("Done — next step").performClick() }
        compose.onNodeWithText("Done — finish").performClick()
        compose.waitForIdle()
    }

    @Test
    fun finishingShowsTheChangesAndOneConfirmAppliesThem() {
        cookToTheEnd()

        compose.onNodeWithText("Update the pantry").assertIsDisplayed()
        compose.onNodeWithContentDescription("milk, from 4 cups to 3 cups").assertIsOn()
        compose.onNodeWithText("Can't work out how much was used").assertIsDisplayed()
        compose.onNodeWithContentDescription("flour: Keep").assertIsSelected()

        compose.onNodeWithContentDescription("flour: Running low").performClick()
        compose.onNodeWithTag("useUpButton").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf("3 cups", null), pantry.items.value.map { it.quantity })
        assertEquals(listOf(true, true), pantry.items.value.map { it.inStock })
        assertEquals(listOf("flour"), groceries.items.value.map { it.text })
        compose.onNodeWithText("Pantry updated").assertIsDisplayed()

        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf(milk, flour), pantry.items.value)
        assertEquals(emptyList<String>(), groceries.items.value.map { it.text })
    }

    @Test
    fun anUntickedRowIsLeftAlone() {
        cookToTheEnd()

        compose.onNodeWithContentDescription("milk, from 4 cups to 3 cups").performClick()
        compose.onNodeWithTag("useUpButton").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf(milk, flour), pantry.items.value)
        compose.onNodeWithText("Pantry updated").assertDoesNotExist()
    }
}
