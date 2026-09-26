package com.example.recipeclipper.walkthrough

import androidx.compose.ui.test.hasText
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.di.ChefModelModule
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Test
import java.time.LocalDate

/** Walkthroughs 01–05 (#106): the tab bar, Week, Groceries, Pantry, menus, expiry reminders. */
@HiltAndroidTest
@UninstallModules(ChefModelModule::class)
class MealPlanWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener()

    private val today = LocalDate.now().toEpochDay()

    private fun planToday(title: String) {
        scrollTo("weekList", "addToDay-$today")
        tapTag("addToDay-$today")
        tap(title)
    }

    @Test
    fun test01_tabsAndWeek() {
        start("mealPlan")
        tap("Chicken Adobo")
        menu("Add to plan")
        tapTag("planDay-$today")
        tap(hasText("Add to ", substring = true))
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
        for (title in listOf("Chicken Adobo", "Weeknight Chili")) {
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
}
