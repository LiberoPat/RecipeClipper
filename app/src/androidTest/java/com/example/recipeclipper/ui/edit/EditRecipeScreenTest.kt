package com.example.recipeclipper.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeRecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Edit screen (#29) over a real ViewModel and a fake repository. */
@RunWith(AndroidJUnit4::class)
class EditRecipeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()

    private fun showNew(onSaved: (Long) -> Unit) {
        val viewModel = EditRecipeViewModel(SavedStateHandle(), repository)
        compose.setContent { EditRecipeScreen(onBack = {}, onSaved = onSaved, viewModel = viewModel) }
    }

    @Test
    fun typingARecipeInAndSavingOpensIt() {
        repository.addManualResult = Recipe(
            name = "Toast", image = null, ingredients = listOf("Bread"), instructions = emptyList(),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "manual:x", id = 9L
        )
        var saved: Long? = null
        showNew { saved = it }

        compose.onNodeWithText("New recipe").assertIsDisplayed()
        compose.onNodeWithText("Name").performTextInput("Toast")
        compose.onNodeWithText("Ingredients, one per line").performScrollTo().performTextInput("Bread\nButter")
        // Save sits at the top of the scrolling column: bring it back before tapping it.
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf("Bread", "Butter"), repository.addManualCalls.single().ingredients)
        assertEquals(9L, saved)
    }

    @Test
    fun savingWithoutIngredientsOrStepsSaysWhatIsMissing() {
        var saved: Long? = null
        showNew { saved = it }

        compose.onNodeWithText("Name").performTextInput("Toast")
        compose.onNodeWithText("Save").performClick()

        compose.onNodeWithText("A recipe needs a name, and ingredients or steps.").assertIsDisplayed()
        assertNull(saved)
    }
}
