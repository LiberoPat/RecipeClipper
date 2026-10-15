package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.model.PageRecipeCheck.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The model may only pick text on the page (#103). The corpus's `Pick` rows pin iOS to this. */
class PageRecipeCheckTest {

    private val page = """
        Grandma’s Banana Bread
        Servings: 10 slices
        Ingredients
        ▢ 3 very ripe bananas, mashed
        ⅓ cup melted butter
        1 ½ cups all-purpose flour
        12 cups popcorn
        Instructions
        1. Preheat the oven to 350°F (175°C). Bake for 55 to 65 minutes, until a tester comes out clean.
        Bake for 20-25 minutes.
    """.trimIndent()

    private fun find(picked: String, kind: Kind) = PageRecipeCheck.find(page, picked, kind)

    @Test fun `what shows is the page's own text, found after folding`() {
        assertEquals("⅓ cup melted butter", find("1/3 cup melted butter", Kind.INGREDIENT))
        assertEquals("Grandma’s Banana Bread", find("grandma's banana bread", Kind.NAME))
        assertEquals("1 ½ cups all-purpose flour", find("1 1/2 cups all-purpose flour", Kind.INGREDIENT))
        assertEquals("3 very ripe bananas, mashed", find("3 very ripe bananas, mashed", Kind.INGREDIENT))
    }

    @Test fun `text that isn't on the page is dropped`() {
        assertNull(find("2 cups melted butter", Kind.INGREDIENT))
        assertNull(find("1/2 cup melted butter", Kind.INGREDIENT))
        assertNull(find("Mash the bananas.", Kind.STEP))
    }

    @Test fun `a span never cuts into a number or a word`() {
        assertNull(find("2 cups popcorn", Kind.INGREDIENT))
        assertNull(find("½ cups all-purpose flour", Kind.INGREDIENT))
        assertNull(find("25 minutes.", Kind.STEP))
        assertNull(find("Bake for 20", Kind.STEP))
        assertNull(find("ake for 55 to 65 minutes", Kind.STEP))
        assertEquals("Bake for 55 to 65 minutes, until a tester comes out clean.",
            find("Bake for 55 to 65 minutes, until a tester comes out clean.", Kind.STEP))
    }

    @Test fun `an ingredient starts its line, after a bullet only`() {
        assertNull(find("10 slices", Kind.INGREDIENT))
        assertEquals("10 slices", find("10 slices", Kind.OTHER))
    }

    @Test fun `dashes fold, and a name or step needs a letter`() {
        assertEquals("Bake for 20-25 minutes.", find("Bake for 20–25 minutes.", Kind.STEP))
        assertNull(find("350", Kind.STEP))
        assertNull(find("", Kind.NAME))
    }

    @Test fun `a recipe needs a name plus ingredients or steps`() {
        val picked = PageSelection(
            name = "Grandma's Banana Bread",
            ingredients = listOf("⅓ cup melted butter", "2 eggs"),
            steps = listOf("Mash everything."),
            yield = "10 slices", prepTime = "15 minutes"
        )
        val kept = PageRecipeCheck.verify(page, picked)!!
        assertEquals("Grandma’s Banana Bread", kept.name)
        assertEquals(listOf("⅓ cup melted butter"), kept.ingredients)
        assertEquals(emptyList<String>(), kept.steps)
        assertEquals("10 slices", kept.yield)
        assertNull(kept.prepTime)
        assertNull(PageRecipeCheck.verify(page, picked.copy(name = "Banana Loaf")))
        assertNull(PageRecipeCheck.verify(page, picked.copy(ingredients = listOf("2 eggs"))))
    }

    // --- One recipe's lines only (#128) ---

    /** Delish's card as the #105 evaluation's window read it (trimmed), then another recipe's card. */
    private val delish = """
        Creamy Tuscan Chicken
        Download the Delish app for free!
        Ingredients
        1 Tbsp. extra-virgin olive oil
        4 (6- to 8-oz.) boneless, skinless chicken breasts
        Kosher salt
        3 Tbsp. unsalted butter
        1 1/2 cups cherry tomatoes, halved
        3 cups baby spinach
        1/2 cup heavy cream
        Directions
        Step 1In a large skillet over medium heat, heat oil.
        Step 2Stir in cream and Parmesan and bring to a simmer.
        LIKE THIS RECIPE? THEN YOU'LL LOVE:
        Creamy Tuscan Orzo
        35 mins
        Ingredients
        1 cup orzo
        2 cups low-sodium chicken broth
        1/2 cup heavy cream
        Directions
        Step 1Bring the broth to a boil and stir in the orzo.
    """.trimIndent()

    private val tuscan = listOf(
        "1 Tbsp. extra-virgin olive oil", "4 (6- to 8-oz.) boneless, skinless chicken breasts", "Kosher salt",
        "3 Tbsp. unsalted butter", "1 1/2 cups cherry tomatoes, halved", "3 cups baby spinach", "1/2 cup heavy cream"
    )
    private val tuscanSteps = listOf(
        "Step 1In a large skillet over medium heat, heat oil.", "Step 2Stir in cream and Parmesan and bring to a simmer."
    )

    @Test fun `every line of the recipe's own card is kept`() {
        val kept = PageRecipeCheck.verify(delish, PageSelection("Creamy Tuscan Chicken", tuscan, tuscanSteps))!!
        assertEquals(tuscan, kept.ingredients)
        assertEquals(tuscanSteps, kept.steps)
    }

    @Test fun `lines found only in another recipe's card are dropped`() {
        val picked = PageSelection(
            "Creamy Tuscan Chicken",
            tuscan.take(3) + "1 cup orzo" + tuscan.drop(3) + "2 cups low-sodium chicken broth",
            tuscanSteps + "Step 1Bring the broth to a boil and stir in the orzo."
        )
        val kept = PageRecipeCheck.verify(delish, picked)!!
        assertEquals(tuscan, kept.ingredients)
        assertEquals(tuscanSteps, kept.steps)
    }

    @Test fun `the recipe is the card holding most of the picked lines`() {
        val orzo = PageSelection(
            "Creamy Tuscan Orzo", listOf("1 cup orzo", "2 cups low-sodium chicken broth", "1/2 cup heavy cream", "Kosher salt"),
            listOf("Step 1Bring the broth to a boil and stir in the orzo.")
        )
        val kept = PageRecipeCheck.verify(delish, orzo)!!
        assertEquals(listOf("1 cup orzo", "2 cups low-sodium chicken broth", "1/2 cup heavy cream"), kept.ingredients)
        assertEquals(orzo.steps, kept.steps)
    }

    @Test fun `a second ingredients heading before the steps is the same recipe`() {
        val cake = """
            Lemon Cake
            Ingredients
            2 cups flour
            1 cup sugar
            Ingredients for the glaze
            1 cup powdered sugar
            Instructions
            Mix the flour and sugar.
            Whisk the powdered sugar with lemon juice.
        """.trimIndent()
        val picked = PageSelection(
            "Lemon Cake", listOf("2 cups flour", "1 cup sugar", "1 cup powdered sugar"),
            listOf("Mix the flour and sugar.", "Whisk the powdered sugar with lemon juice.")
        )
        assertEquals(picked, PageRecipeCheck.verify(cake, picked))
    }
}
