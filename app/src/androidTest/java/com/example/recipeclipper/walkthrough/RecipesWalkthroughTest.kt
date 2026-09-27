package com.example.recipeclipper.walkthrough

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.di.OnDeviceModelModule
import com.example.recipeclipper.di.SourceModule
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Test

/**
 * Walkthroughs 06–10 (#106): the Recipes screen, amounts in steps, Chef mode with a stub model
 * (an emulator has none), the free tier, and a recipe picked from the page text (seeded as
 * such: no model runs). A pasted link opens a canned recipe, as iOS's UI-test source does, so
 * no clip depends on the network.
 */
@HiltAndroidTest
@UninstallModules(OnDeviceModelModule::class, SourceModule::class)
class RecipesWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val source: RecipeSource = object : RecipeSource {
        override suspend fun fetch(url: String): ParseResult = ParseResult.Success(Recipe(
            name = "Stub Chicken Soup", image = null,
            ingredients = listOf("1 whole chicken", "2 carrots", "8 cups water"),
            instructions = listOf("Simmer everything for 1 hour.", "Season and serve."),
            prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = url
        ))
    }

    @BindValue @JvmField
    val decisionModel: DecisionModel = WalkthroughSeed.decisionModel()

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener(
        support = ChefSupport.Available(setOf("en")),
        written = mutableMapOf(
            WalkthroughSeed.CHEF_STEP to WalkthroughSeed.CHEF_SHORT,
            WalkthroughSeed.WHISK to WalkthroughSeed.WHISK_SHORT
        )
    )

    private fun field(label: String) = hasSetTextAction() and hasText(label, substring = true)

    /** Home's "Recipes ›" row, below the fold: not the Recipes tab, which is Home itself (#152). */
    private fun openRecipes() = tapScrolling(hasText("Recipes") and hasText("›"))

    private fun typeARecipe() {
        tapDescription("Add a recipe")
        tap("Type a recipe")
        waitFor(field("Name"))
        compose.onAllNodes(field("Name"))[0].performTextInput("Weeknight Stew")
        pause(800)
        compose.onAllNodes(field("Ingredients, one per line"))[0].performTextInput("2 carrots\n1 lb stewing beef")
        pause(800)
        tap("Save", 2000)
    }

    @Test
    fun test06_recipesScreen() {
        start()
        openRecipes()
        menu("Name")
        typeARecipe()
        back()
        tapDescription("Add a recipe")
        tap("Paste a link")
        // The dialog's field, not the Recipes search field behind it.
        waitFor(field("Recipe URL"))
        compose.onAllNodes(field("Recipe URL"))[0].performTextInput("https://example.com/chicken-soup")
        pause(800)
        tap("Go", 3000)
    }

    @Test
    fun test07_amountsInSteps() {
        start("amountsInSteps")
        tapDescription("Settings")
        swipeUp()
        tap("Amounts in steps")
        back()
        tap("Weeknight Chili")
        swipeUp()
        pause(1000)
        tap("Start cooking", 2500)
    }

    @Test
    fun test08_chefModeStubModel() {
        start("chefMode")
        tapDescription("Settings")
        swipeUp()
        tap("Chef mode")
        back()
        tap("Sponge Cake")
        swipeUp()
        pause(1000)
        tap(WalkthroughSeed.CHEF_SHORT, 2500) // as written…
        tap(WalkthroughSeed.CHEF_STEP, 2000) // …and short again
        tap("Start cooking", 2000)
        tap("As written", 2500)
    }

    @Test
    fun test09_freeTier() {
        start("freeTier")
        openRecipes()
        waitFor(hasText("20 of 20 recipes"))
        pause(2000)
        typeARecipe()
        waitFor(hasText("Unlock"))
        pause(3000)
        tap("Cancel")
    }

    @Test
    fun test10_pageExtractionLine() {
        start("llmExtraction")
        tap("Grandma's Lentil Soup")
        waitFor(hasText("Picked from the page text", substring = true))
        pause(3000)
        swipeUp()
    }
}
