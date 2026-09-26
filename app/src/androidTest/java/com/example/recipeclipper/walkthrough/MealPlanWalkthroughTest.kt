package com.example.recipeclipper.walkthrough

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.di.OnDeviceModelModule
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Test
import java.time.LocalDate

/** Walkthroughs 01–05 (#106): the tab bar, Week, Groceries, Pantry, menus, expiry reminders. */
@HiltAndroidTest
@UninstallModules(OnDeviceModelModule::class)
class MealPlanWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val decisionModel: DecisionModel = WalkthroughSeed.decisionModel()

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener()

    private val today = LocalDate.now().toEpochDay()

    private fun planToday(title: String) {
        scrollTo("weekList", "addToDay-$today")
        tapTag("addToDay-$today")
        type("addSearch", title.substringBefore(' '), submit = false)
        tap(title)
    }

    @Test
    fun test01_tabsAndWeek() {
        start("mealPlan")
        tap("Chicken Adobo")
        menu("Add to plan")
        tapTag("planDay-$today")
        // The sheet's "Add to Friday" button, not the menu's "Add to plan" as it fades.
        tap(hasText("Add to ", substring = true) and hasClickAction() and !hasText("plan", substring = true))
        back()
        tap("Week")
        planToday("Weeknight Chili")
        tapTag("toggleMonth")
        pause(1000)
        tapTag("monthDay-$today")
        pause(1000)
        tap("Groceries")
        tap("Pantry")
        tap("Recipes")
    }

    @Test
    fun test02_groceries() {
        start("mealPlan")
        for (title in listOf("Chicken Adobo", "Weeknight Chili", "Chicken Adobo")) { // Adobo twice: "× 2"
            tap(title)
            menu("Add to groceries")
            tapTag("addToGroceriesButton")
            back()
        }
        tap("Groceries")
        type("groceryDraft", "milk")
        tap(hasText("chicken thighs", substring = true), 2000)
        tap(hasText("soy sauce", substring = true), 2000)
        swipeUp()
    }

    @Test
    fun test03_pantryAndWhatINeed() {
        start("mealPlan")
        tap("Pantry")
        listOf("soy sauce", "garlic", "bay leaves", "white vinegar").forEach { type("pantryDraft", it) }
        tap("Week")
        planToday("Chicken Adobo")
        menu("What I need")
        pause(1500)
        swipeUp()
        tapTag("addBuyToGroceries")
    }

    @Test
    fun test04_weeklyMenus() {
        start("mealPlan")
        tap("Week")
        planToday("Chicken Adobo")
        planToday("Spaghetti Carbonara")
        menu("Save week as menu…")
        type("menuName", "Weeknights", submit = false)
        tap("Save")
        tapDescription("Next week")
        menu("Apply a menu…")
        tap(hasText("Weeknights"), 2500)
    }

    @Test
    fun test05_expiryReminders() {
        start("mealPlan")
        tapDescription("Settings")
        repeat(3) { swipeUp() }
        tap("Expiry reminders", 2500)
    }

    /**
     * Grocery lines merged with the model's help (#99), its answers simulated
     * ([WalkthroughSeed.decisionModel]): close names become one row, trailing notes are ignored.
     */
    @Test
    fun test11_groceriesAiMergingSimulated() {
        start("mealPlan", "aiDecisions")
        tap("Groceries")
        listOf(listOf("200 g sweetcorn", "100 g corn"), listOf("2 eggs, beaten", "3 eggs"))
            .forEach { pair ->
                pair.forEach { type("groceryDraft", it) }
                pause(2500)
            }
        swipeUp()
        pause(2000)
    }
}
