package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sending a list (#149): "Send list" writes every unticked item as shown, naming the recipes
 * it's for, and a list shared back in is read line by line, as written.
 */
class SendListTextTest {

    private var nextId = 1L
    private fun item(text: String, recipeId: Long? = null, checked: Boolean = false) = GroceryItem(
        id = nextId, text = text, language = "en", aisle = Aisles.of(text, LanguageWords.ENGLISH),
        checked = checked, sortOrder = (nextId++).toInt(), recipeId = recipeId
    )

    private val titles = mapOf(1L to "Sheet-pan chicken", 2L to "Bread", 3L to "Cake")

    private fun send(vararg items: GroceryItem) =
        GroceryShareText.format(GroceryCombiner.sections(items.toList()), "Groceries", titles) { it.key }

    @Test fun eachItemNamesTheRecipeItIsFor() {
        assertEquals(
            "Groceries\n\nproduce\n- 2 onions\n\nmeat\n- 2 lb chicken thighs (Sheet-pan chicken)",
            send(item("2 lb chicken thighs", recipeId = 1), item("2 onions"))
        )
    }

    @Test fun aRowFromSeveralRecipesNamesThemAll() {
        assertEquals(
            "Groceries\n\nbaking\n- 300 g flour (Bread, Cake)",
            send(item("200 g flour", recipeId = 2), item("100 g flour", recipeId = 3))
        )
    }

    @Test fun aRepeatedLineIsShownOnceWithEachRecipeNamedOnce() {
        assertEquals(
            "Groceries\n\nproduce\n- 6 onions (Bread, Cake)",
            send(item("2 onions", recipeId = 2), item("2 onions", recipeId = 3), item("2 onions", recipeId = 3))
        )
    }

    @Test fun linesKeptTogetherEachNameTheirOwnRecipes() {
        assertEquals(
            "Groceries\n\ndairy\n- 1 cup milk (Cake)\n\nbaking\n- 1 cup sugar × 2 (Bread, Cake)\n- 100 g sugar",
            send(
                item("1 cup sugar", recipeId = 2), item("1 cup sugar", recipeId = 3), item("100 g sugar"),
                item("1 cup milk", recipeId = 3), item("2 eggs", recipeId = 3, checked = true)
            )
        )
    }

    @Test fun aRecipeWithNoTitleNamesNothing() {
        assertEquals("Groceries\n\nproduce\n- 2 onions", send(item("2 onions", recipeId = 9)))
    }

    // --- Receiving

    @Test fun aSentListIsReadByItsBulletsLeavingTheTitleAndAislesOut() {
        val sent = "Groceries\n\nProduce\n- 2 onions\n\nMeat\n- 2 lb chicken thighs (Sheet-pan chicken)\n- 2 corn × 3"
        assertEquals(
            listOf("2 onions", "2 lb chicken thighs (Sheet-pan chicken)", "2 corn × 3"),
            ReceivedList.lines(sent)
        )
    }

    @Test fun aListWithNoBulletsOffersEveryLine() {
        assertEquals(listOf("milk", "2 eggs", "bread"), ReceivedList.lines("milk\r\n  2 eggs \n\nbread\n"))
    }

    @Test fun otherBulletsCountAndHeadingsAndEmptyBulletsAreLeftOut() {
        assertEquals(
            listOf("milk", "eggs", "1 lb butter"),
            ReceivedList.lines("Shopping:\n• milk\n* eggs\n– 1 lb butter\n- \n-\n- For the sauce:\n- …")
        )
    }

    @Test fun aNegativeLookingLineIsNotABullet() {
        assertEquals(listOf("-5 bags ice", "milk"), ReceivedList.lines("-5 bags ice\nmilk"))
    }

    @Test fun nothingToAddIsEmpty() {
        assertEquals(emptyList<String>(), ReceivedList.lines(""))
        assertEquals(emptyList<String>(), ReceivedList.lines("\n  \n---\n"))
    }
}
