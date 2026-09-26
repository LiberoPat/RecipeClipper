package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The model names runs of lines; the lines come from the window as written (#128). */
class PageLinesTest {

    private val window = listOf(
        "Grandma’s Banana Bread",
        "Serves 8",
        "Ingredients",
        "▢ 3 very ripe bananas, mashed",
        "• ⅓ cup melted butter",
        "For the topping:",
        "- 1 tbsp sugar",
        "Instructions",
        "Mash the bananas.",
        "Bake for 1 hour."
    ).joinToString("\n")

    @Test fun `each line goes to the model after its number`() {
        assertEquals("[1] Soup\n[2] Ingredients\n[3] 1 cup water", PageLines.numbered("Soup\nIngredients\n1 cup water"))
    }

    @Test fun `the named lines, as written, without bullets or bare headings`() {
        val picked = PageLines.selection(
            window,
            PagePick("Grandma’s Banana Bread", listOf(LineRun(3, 7)), listOf(LineRun(8, 10)), yield = "Serves 8")
        )
        assertEquals(
            PageSelection(
                "Grandma’s Banana Bread",
                listOf("3 very ripe bananas, mashed", "⅓ cup melted butter", "For the topping:", "1 tbsp sugar"),
                listOf("Mash the bananas.", "Bake for 1 hour."),
                yield = "Serves 8"
            ),
            picked
        )
    }

    @Test fun `runs in any order and overlapping give each line once, in page order`() {
        val picked = PageLines.selection(window, PagePick("x", listOf(LineRun(7, 7), LineRun(4, 5), LineRun(5, 5)), emptyList()))
        assertEquals(listOf("3 very ripe bananas, mashed", "⅓ cup melted butter", "1 tbsp sugar"), picked.ingredients)
    }

    @Test fun `a run outside the window or backwards is no answer, never a guess`() {
        val picked = PageLines.selection(
            window,
            PagePick("x", listOf(LineRun(0, 4), LineRun(5, 4), LineRun(9, 11)), listOf(LineRun(-2, -1), LineRun(10, 10)))
        )
        assertEquals(emptyList<String>(), picked.ingredients)
        assertEquals(listOf("Bake for 1 hour."), picked.steps)
    }

    @Test fun `a line named as both an ingredient and a step is neither`() {
        val picked = PageLines.selection(window, PagePick("x", listOf(LineRun(4, 9)), listOf(LineRun(9, 10))))
        assertEquals(listOf("3 very ripe bananas, mashed", "⅓ cup melted butter", "For the topping:", "1 tbsp sugar"), picked.ingredients)
        assertEquals(listOf("Bake for 1 hour."), picked.steps)
    }

    @Test fun `only a heading on its own is dropped, in every shipped language`() {
        for (heading in listOf("Ingredients", "INGREDIENTS:", "Method", "Zutaten", "作り方")) {
            assertEquals(heading, true, RecipeTextWindow.isBareHeading(heading))
        }
        for (line in listOf("Ingredients for the glaze", "材料（2人分）", "Method: stir well")) {
            assertEquals(line, false, RecipeTextWindow.isBareHeading(line))
        }
    }
}
