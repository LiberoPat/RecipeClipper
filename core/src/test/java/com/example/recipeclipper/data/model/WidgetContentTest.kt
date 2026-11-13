package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The home-screen widgets' content (#309); iOS's `WidgetContentTests` has the same cases. */
class WidgetContentTest {

    private val types = listOf(
        MealType(id = 3, name = "Dinner", builtInKey = "dinner", sortOrder = 2),
        MealType(id = 1, name = "Breakfast", builtInKey = "breakfast", sortOrder = 0),
        MealType(id = 9, name = "Pudding", builtInKey = null, sortOrder = 3)
    )

    private fun meal(id: Long, day: Long, type: Long, title: String? = null, note: String? = null) =
        PlannedMeal(id = id, day = day, mealTypeId = type, recipeId = title?.let { id }, title = title,
            imageUrl = null, servings = null, note = note)

    @Test fun tonightIsTodaysMealsInMealTypeOrder() {
        val meals = listOf(
            meal(1, day = 100, type = 3, title = "Focaccia"),
            meal(2, day = 100, type = 9, note = " Ice cream "),
            meal(3, day = 101, type = 1, title = "Pancakes"),
            meal(4, day = 100, type = 1, title = "Porridge"),
            meal(5, day = 100, type = 3, note = "Salad")
        )
        assertEquals(
            listOf(
                WidgetContent.Meal("Breakfast", "Porridge"),
                WidgetContent.Meal("Dinner", "Focaccia"),
                WidgetContent.Meal("Dinner", "Salad"),
                WidgetContent.Meal("Pudding", "Ice cream")
            ),
            WidgetContent.tonight(meals, types, today = 100)
        )
    }

    @Test fun aMealWithNothingToShowIsLeftOut() {
        assertEquals(emptyList<WidgetContent.Meal>(), WidgetContent.tonight(listOf(meal(1, 100, 3, note = "  ")), types, 100))
    }

    private var nextId = 1L
    private fun item(text: String, checked: Boolean = false) = GroceryItem(
        id = nextId, text = text, language = "en", aisle = Aisles.of(text, LanguageWords.forTag("en")),
        checked = checked, sortOrder = (nextId++).toInt()
    )

    @Test fun groceriesAreTheUntickedLinesAsShown() {
        val sections = GroceryCombiner.sections(
            listOf(
                item("200 g flour"), item("100 g flour"), item("2 onions"), item("1 cup milk", checked = true),
                item("1 egg"), item("1 egg"), item("paper towels")
            )
        )
        // Produce, dairy, baking, other; the ticked milk is in the basket.
        assertEquals(listOf("2 onions", "2 eggs", "300 g flour", "paper towels"), WidgetContent.groceries(sections))
    }

    @Test fun aListThatFitsIsShownWhole() {
        assertEquals(WidgetContent.Fit(shown = 4, more = 0, inline = false), WidgetContent.fit(count = 4, rows = 4))
        assertEquals(WidgetContent.Fit(shown = 0, more = 0, inline = false), WidgetContent.fit(count = 0, rows = 4))
    }

    @Test fun aLongerListKeepsItsLastRowForHowManyMore() {
        assertEquals(WidgetContent.Fit(shown = 3, more = 17, inline = false), WidgetContent.fit(count = 20, rows = 4))
        assertEquals(WidgetContent.Fit(shown = 1, more = 19, inline = false), WidgetContent.fit(count = 20, rows = 2))
    }

    // #329: one row used to be "+20 more" and no item at all.
    @Test fun oneRowStillNamesAnItemWithTheCountBesideIt() {
        assertEquals(WidgetContent.Fit(shown = 1, more = 19, inline = true), WidgetContent.fit(count = 20, rows = 1))
        assertEquals(WidgetContent.Fit(shown = 1, more = 1, inline = true), WidgetContent.fit(count = 2, rows = 0))
        assertEquals(WidgetContent.Fit(shown = 1, more = 0, inline = false), WidgetContent.fit(count = 1, rows = 0))
    }
}
