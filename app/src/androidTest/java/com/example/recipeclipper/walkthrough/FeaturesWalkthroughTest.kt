package com.example.recipeclipper.walkthrough

import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.di.OnDeviceModelModule
import com.example.recipeclipper.fake.FakeDecisionModel
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Test
import java.time.LocalDate

/**
 * Walkthroughs 35–37 and 40 (#106): the pantry's amounts beside the week's (#302), Use it up
 * (#305), text shared in (#306) and printing (#318). The model supports nothing here.
 */
@HiltAndroidTest
@UninstallModules(OnDeviceModelModule::class)
class FeaturesWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val decisionModel: DecisionModel = FakeDecisionModel(languages = emptySet())

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener(support = ChefSupport.Unsupported)

    private val today = LocalDate.now().toEpochDay()

    private fun plan(day: Long, title: String) {
        scrollTo("weekList", "addToDay-$day")
        tapTag("addToDay-$day")
        type("addSearch", title.substringBefore(' '), submit = false)
        tap(title)
    }

    /**
     * An amount typed with the name (#302): "3 onions", "2 cans tomatoes" and "3 lb chicken
     * thighs", each shown on its row at once; then the Adobo planned twice and What I need's
     * "You have 3 lb · this week's recipes use 4 lb" under In your pantry.
     */
    @Test
    fun test35_pantryAmounts() {
        start()
        tap("Pantry")
        listOf("3 onions", "2 cans tomatoes", "3 lb chicken thighs").forEach { type("pantryDraft", it); pause(1200) }
        pause(2500)
        tap("Week")
        plan(today, "Chicken Adobo")
        plan(today + 1, "Chicken Adobo")
        menu("What I need")
        show("this week's recipes use", 4000)
    }

    /**
     * Use it up (#305): the soy sauce, due tomorrow, says "2 saved recipes use it"; the sheet
     * ("Soy sauce · use by tomorrow", its recipes A–Z) plans one for tomorrow's dinner, and the
     * Week shows it.
     */
    @Test
    fun test36_useItUp() {
        start("useItUp", kitchen = true, expiringTomorrow = "soy sauce")
        tap("Pantry", 2000)
        val soy = pantryIds.getValue("soy sauce")
        scrollTo("pantryList", "use-it-up-$soy")
        pause(3000)
        tapTag("use-it-up-$soy")
        waitFor(hasText("Soy sauce · use by tomorrow"))
        pause(3000)
        tap("Plan tomorrow", 2500) // the first: Beef Stir-Fry
        waitFor(hasText("Planned tomorrow"))
        back() // closes the sheet
        tap("Week")
        scrollTo("weekList", "addToDay-${today + 1}")
        pause(3500)
    }

    /** Text shared in (#306), as another app's Share would send it. */
    private fun shareText(text: String) =
        startFromApp(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))

    /**
     * Text shared in (#306): a relative's recipe opens sorted in the editor, its leftovers (the
     * greeting, the tip, a link) under Notes, is named and saved; then a bulleted list goes to
     * "Add this list".
     */
    @Test
    fun test37_shareText() {
        start("shareText")
        shareText(SCONES)
        waitFor(hasText("Check the recipe"))
        pause(3000)
        show("are under Notes", 2000)
        show("Tip: serve warm with jam", 3000)
        val name = hasSetTextAction() and hasText("Name")
        reveal(name)
        compose.onAllNodes(name)[0].performTextInput("Grandma's Scones")
        pause(1500)
        tap("Save", 3000)
        shareText(LIST)
        waitFor(hasText("Add this list"))
        pause(3000)
        tapTag("receiveToGroceries")
        pause(3000)
    }

    /**
     * Print or save as PDF (#318) from the recipe's menu: the system print dialog with its
     * preview; then a list's "Make a cookbook": "Making the cookbook…", then the share sheet with
     * the PDF.
     */
    @Test
    fun test40_printAndCookbook() {
        start("print")
        tap("Chicken Adobo")
        menu("Print or save as PDF")
        waitForSystemScreen(6000) // the print dialog
        systemBack()
        back()
        tapScrolling(hasText("Lists") and hasText("›"))
        tap("Dinner", 2000)
        menu("Make a cookbook")
        waitForSystemScreen() // the share sheet, with "Dinner.pdf"
        systemBack()
    }

    companion object {
        val SCONES = """
            Hi love, here are Grandma's scones as promised.

            Ingredients:
            - 2 cups flour
            - 1 tbsp sugar
            - 1 tsp salt
            - 1/2 cup cold butter
            - 3/4 cup milk

            Method:
            1. Mix the flour, sugar and salt, then rub in the butter.
            2. Stir in the milk and pat into rounds.
            3. Bake at 425°F for 15 minutes.

            Tip: serve warm with jam.
            https://example.com/family-scones
        """.trimIndent()
        const val LIST = "Saturday\n- 6 eggs\n- 1 cup plain yogurt\n- 2 lemons\n- a bunch of basil"
    }
}
