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

    // --- The Pantry's "Send list": what's in stock

    private fun stock(
        name: String,
        quantity: String? = null,
        inStock: Boolean = true,
        aisle: Aisle = Aisles.of(name, LanguageWords.ENGLISH),
        expires: Long? = null
    ) = PantryItem(nextId++, name, quantity, "en", aisle, inStock, alwaysHave = false, purchasedDay = null, expiresDay = expires)

    private fun sendPantry(sort: PantrySort, vararg items: PantryItem) =
        PantryShareText.format(PantryList.arrange(items.toList(), "", sort), "Pantry") { it.key }

    @Test fun thePantrySendsWhatIsInStockByAisleWithQuantitiesAsWritten() {
        assertEquals(
            "Pantry\n\nproduce\n- onions\n\ngrains\n- basmati rice (half a bag)\n- oats",
            sendPantry(
                PantrySort.AISLE,
                stock("basmati rice", quantity = " half a bag "), stock("onions"), stock("oats", quantity = " ", aisle = Aisle.GRAINS),
                stock("milk", inStock = false)
            )
        )
    }

    @Test fun anAisleWithNothingInStockIsLeftOut() {
        assertEquals("Pantry\n\nproduce\n- onions", sendPantry(PantrySort.AISLE, stock("onions"), stock("milk", inStock = false)))
    }

    @Test fun sortedByExpiryThePantrySendsOneListWithNoHeading() {
        assertEquals(
            "Pantry\n\n- milk (1 l)\n- onions\n- rice",
            sendPantry(PantrySort.EXPIRY, stock("rice"), stock("onions", expires = 20_730), stock("milk", "1 l", expires = 20_725))
        )
    }

    @Test fun aPantryWithNothingInStockSendsOnlyItsTitle() {
        assertEquals("Pantry", sendPantry(PantrySort.AISLE, stock("milk", inStock = false)))
    }

    // What the Pantry sends reads back as its items, and adds to another pantry as their names.
    @Test fun thePantrysListReadsBackAsItsItems() {
        val sent = sendPantry(PantrySort.AISLE, stock("basmati rice", quantity = "half a bag"), stock("onions"), stock("2 lemons"))
        val lines = ReceivedList.lines(sent)
        assertEquals(listOf("2 lemons", "onions", "basmati rice (half a bag)"), lines)
        assertEquals(listOf("lemons", "onions", "basmati rice"), lines.map { IngredientName.of(it, LanguageWords.ENGLISH) })
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
