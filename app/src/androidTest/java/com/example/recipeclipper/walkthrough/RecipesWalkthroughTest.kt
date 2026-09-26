package com.example.recipeclipper.walkthrough

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.di.ChefModelModule
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Test

/**
 * Walkthroughs 06–10 (#106): the Recipes screen, amounts in steps, Chef mode with a stub model
 * (an emulator has none), the free tier, and a recipe picked from the page text (seeded as
 * such: no model runs).
 */
@HiltAndroidTest
@UninstallModules(ChefModelModule::class)
class RecipesWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener(
        support = ChefSupport.Available(setOf("en")),
        written = mutableMapOf(
            WalkthroughSeed.CHEF_STEP to WalkthroughSeed.CHEF_SHORT,
            WalkthroughSeed.WHISK to WalkthroughSeed.WHISK_SHORT
        )
    )

    private fun field(label: String) = hasSetTextAction() and hasText(label, substring = true)

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
        tap("Recipes")
        swipeUp()
        menu("Name")
        typeARecipe()
        back()
        tapDescription("Add a recipe")
        tap("Paste a link")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("https://example.com/chicken-soup")
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
        tap(WalkthroughSeed.CHEF_SHORT, 2500)
        tap("Start cooking", 2000)
        tap("As written", 2500)
    }

    @Test
    fun test09_freeTier() {
        start("freeTier")
        tap("Recipes")
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
