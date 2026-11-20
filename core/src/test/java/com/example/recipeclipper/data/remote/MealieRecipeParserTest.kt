package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One Mealie recipe's JSON (#391): what is detected, and each field's rule. iOS's `MealieRecipeParserTests` mirrors it. */
class MealieRecipeParserTest {

    private fun recipe(json: String): MealieRecipe =
        (MealieRecipeParser.parse(json) as MealieEntry.Recipe).recipe

    private fun ingredient(fields: String) = """{"reference_id": "r", $fields}"""

    private fun withIngredients(vararg ingredients: String, extra: String = "") =
        """{"slug": "s", "name": "Soup", "recipe_ingredient": [${ingredients.joinToString(",")}], "recipe_instructions": []$extra}"""

    private val snake = """
        {"id": "8a6b", "slug": "pasta-e-ceci", "name": "  Pasta e Ceci ", "description": "Not kept.", "recipe_servings": 4.0,
         "recipe_yield": "4 servings", "prep_time": "10 minutes", "perform_time": "35 minutes", "total_time": "45 minutes",
         "org_url": "http://www.Example.com/pasta?utm_source=mealie&id=9#top",
         "recipe_category": [{"name": "Dinner"}, {"name": " "}, {"name": "Soup"}], "tags": [{"name": "Vegetarian"}],
         "recipe_ingredient": [
           {"title": "The base", "original_text": "2 cups chickpeas", "display": "ignored", "reference_id": "a"},
           {"original_text": "1 tbsp oil", "reference_id": "b"}],
         "recipe_instructions": [{"title": "", "text": "Heat the oil."}, {"title": null, "text": "Simmer."}],
         "notes": [{"title": "Storage", "text": "Keeps 3 days."}, {"title": "", "text": "Thickens."}],
         "rating": 5, "unknown": {"deep": [1, [2]]}}
    """.trimIndent()

    @Test fun `a snake_case recipe is read field by field`() {
        val r = recipe(snake)
        assertEquals("pasta-e-ceci", r.slug)
        assertEquals("8a6b", r.id)
        assertEquals("Pasta e Ceci", r.name)
        assertEquals(listOf("The base:", "2 cups chickpeas", "1 tbsp oil"), r.ingredients)
        assertEquals(listOf("Heat the oil.", "Simmer."), r.instructions)
        assertEquals("4 servings", r.servings)
        assertEquals("10 minutes", r.prepTime)
        assertEquals("35 minutes", r.cookTime)
        assertEquals("45 minutes", r.totalTime)
        assertEquals("https://www.example.com/pasta?id=9", r.sourceUrl)
        assertEquals(listOf("Dinner", "Soup", "Vegetarian"), r.categories)
        assertEquals("Storage\nKeeps 3 days.\n\nThickens.", r.notes)
    }

    @Test fun `a camelCase recipe reads the same`() {
        val camel = """
            {"id": "x", "slug": "tea", "name": "Tea", "recipeServings": 2, "recipeYield": "", "performTime": "5 minutes",
             "orgURL": "https://example.com/tea", "recipeCategory": [{"name": "Drinks"}], "tags": [{"name": "Quick"}],
             "recipeIngredient": [{"originalText": "1 handful mint", "referenceId": "a"}],
             "recipeInstructions": [{"text": "Steep."}], "notes": [{"title": "T", "text": "N"}]}
        """.trimIndent()
        val r = recipe(camel)
        assertEquals(listOf("1 handful mint"), r.ingredients)
        assertEquals(listOf("Steep."), r.instructions)
        // No yield text, so the number of servings stands in, whole.
        assertEquals("2", r.servings)
        assertEquals("5 minutes", r.cookTime)
        assertEquals("https://example.com/tea", r.sourceUrl)
        assertEquals(listOf("Drinks", "Quick"), r.categories)
        assertEquals("T\nN", r.notes)
    }

    @Test fun `a file with an ISO or seconds time keeps it for the time rules`() {
        val r = recipe(withIngredients(ingredient(""""original_text": "1 egg""""), extra = """, "prep_time": "PT20M", "cookTime": "1 hour 30 minutes", "total_time_seconds": 5400"""))
        assertEquals("PT20M", r.prepTime)
        assertEquals("1 hour 30 minutes", r.cookTime)
        assertEquals("PT5400S", r.totalTime)
        // The time text wins over a seconds figure.
        val both = recipe(withIngredients(ingredient(""""original_text": "1 egg""""), extra = """, "prepTime": "5 min", "prepTimeSeconds": 600"""))
        assertEquals("5 min", both.prepTime)
        val zero = recipe(withIngredients(ingredient(""""original_text": "1 egg""""), extra = """, "prep_time_seconds": 0, "total_time": null"""))
        assertNull(zero.prepTime)
        assertNull(zero.totalTime)
    }

    @Test fun `servings text wins, a number is a fallback, and zero or fractions are handled`() {
        fun servings(extra: String) = recipe(withIngredients(ingredient(""""original_text": "1 egg""""), extra = extra)).servings
        assertEquals("Serves 6", servings(""", "recipe_yield": "Serves 6", "recipe_servings": 4"""))
        assertEquals("4", servings(""", "recipe_servings": 4.0"""))
        assertEquals("2.5", servings(""", "recipe_servings": 2.5"""))
        assertNull(servings(""", "recipe_servings": 0"""))
        assertNull(servings(""", "recipe_servings": "lots""""))
    }

    @Test fun `an ingredient line is its original text, then Mealie's display`() {
        val r = recipe(withIngredients(
            ingredient(""""original_text": " 2 cups flour ", "display": "x", "quantity": 7, "unit": {"name": "cup"}, "food": {"name": "flour"}"""),
            ingredient(""""original_text": "", "display": "3 eggs", "quantity": 3, "food": {"name": "egg"}"""),
            ingredient(""""display": "1 pinch salt", "food": {"name": "salt"}, "unit": {"name": "pinch"}""")
        ))
        assertEquals(listOf("2 cups flour", "3 eggs", "1 pinch salt"), r.ingredients)
    }

    @Test fun `a pre 3_1 line stored with quantity 1 is its note, never a rebuilt number`() {
        val r = recipe(withIngredients(
            // Mealie before 3.1: quantity 1, no unit, no food, the text in the note, and a display with the 1 in front.
            ingredient(""""quantity": 1.0, "unit": null, "food": null, "note": "2 cups red lentils", "display": "1 2 cups red lentils", "original_text": null"""),
            ingredient(""""quantity": 1.0, "note": "salt to taste", "display": "1 salt to taste", "disable_amount": true"""),
            // A line with nothing but a quantity has no text: it is dropped, not shown as "1".
            ingredient(""""quantity": 1.0, "unit": null, "food": null, "note": "", "display": "1", "original_text": null""")
        ))
        assertEquals(listOf("2 cups red lentils", "salt to taste"), r.ingredients)
    }

    @Test fun `with no display either, the food and the note are the text, without an amount`() {
        val r = recipe(withIngredients(
            ingredient(""""quantity": 2.0, "unit": {"name": "cup"}, "food": {"name": "flour"}, "note": "sifted""""),
            ingredient(""""quantity": 5.0, "food": "pepper""""),
            ingredient(""""quantity": 9.0, "unit": {"name": "cup"}""")
        ))
        assertEquals(listOf("flour, sifted", "pepper"), r.ingredients)
    }

    @Test fun `a title puts a heading before its line, and a title already ending in a colon is not doubled`() {
        val r = recipe(withIngredients(
            ingredient(""""title": "For the sauce", "original_text": "1 cup cream""""),
            ingredient(""""title": "To serve:", "original_text": "parsley""""),
            ingredient(""""title": "Empty section", "original_text": null""")
        ))
        assertEquals(listOf("For the sauce:", "1 cup cream", "To serve:", "parsley", "Empty section:"), r.ingredients)
    }

    @Test fun `a step's title goes in front of its text, and a step of only a title in front of the next`() {
        val r = recipe("""
            {"slug": "s", "name": "Soup", "recipe_ingredient": [{"original_text": "1 egg", "reference_id": "a"}],
             "recipe_instructions": [{"title": "Sauce", "text": "Whisk."}, {"title": "Bake:", "text": "Bake 20 minutes."},
                                     {"title": "To finish", "text": ""}, {"title": "", "text": "Serve."}, {"title": "Dangling", "text": ""}]}
        """.trimIndent())
        assertEquals(listOf("Sauce: Whisk.", "Bake: Bake 20 minutes.", "To finish: Serve."), r.instructions)
    }

    @Test fun `a link that isn't a web link is none`() {
        for (url in listOf("", "n/a", "Grandma", "ftp://x.example.com/r", "file:///etc/passwd", "javascript:alert(1)")) {
            assertNull(url, recipe(withIngredients(ingredient(""""original_text": "1 egg""""), extra = """, "orgURL": "$url"""")).sourceUrl)
        }
    }

    @Test fun `a file with no name, or neither ingredients nor steps, is Mealie-shaped but not a recipe`() {
        assertEquals(MealieEntry.NotARecipe, MealieRecipeParser.parse(withIngredients(ingredient(""""original_text": "1 egg"""")).replace("\"Soup\"", "\"\"")))
        assertEquals(MealieEntry.NotARecipe, MealieRecipeParser.parse("""{"slug": "s", "name": "Only", "recipe_ingredient": [], "recipe_instructions": []}"""))
        // Headings alone are no ingredients.
        assertEquals(MealieEntry.NotARecipe, MealieRecipeParser.parse(withIngredients(ingredient(""""title": "Group", "original_text": null"""))))
        // Steps alone are enough, and an empty ingredient list is told by having steps.
        assertTrue(MealieRecipeParser.parse("""{"slug": "s", "name": "Toast", "recipe_ingredient": [], "recipe_instructions": [{"text": "Toast it."}]}""") is MealieEntry.Recipe)
    }

    @Test fun `what is not Mealie's is told apart`() {
        // schema.org: a recipe page's data, which the link import reads.
        val schema = """{"@context": "https://schema.org", "@type": "Recipe", "name": "A", "slug": "a", "recipeIngredient": [{"referenceId": "x"}]}"""
        assertEquals(MealieEntry.NotMealie, MealieRecipeParser.parse(schema))
        assertEquals(MealieEntry.NotMealie, MealieRecipeParser.parse("""{"name": "A", "recipe_ingredient": [{"reference_id": "x"}]}"""))
        assertEquals(MealieEntry.NotMealie, MealieRecipeParser.parse("""{"slug": "  ", "name": "A", "recipe_ingredient": [{"reference_id": "x"}]}"""))
        // Strings in the ingredients, or objects with no referenceId: another app's shape.
        assertEquals(MealieEntry.NotMealie, MealieRecipeParser.parse("""{"slug": "a", "name": "A", "recipe_ingredient": ["1 egg"]}"""))
        assertEquals(MealieEntry.NotMealie, MealieRecipeParser.parse("""{"slug": "a", "name": "A", "recipe_ingredient": [{"original_text": "1 egg"}]}"""))
        assertEquals(MealieEntry.NotMealie, MealieRecipeParser.parse("""{"slug": "a", "name": "A"}"""))
        // A Mealie-shaped file with a slug and a bare empty list and no steps key can't be told.
        assertEquals(MealieEntry.NotMealie, MealieRecipeParser.parse("""{"slug": "a", "name": "A", "recipe_ingredient": []}"""))
    }

    @Test fun `text that is not an object is unreadable`() {
        for (text in listOf("", "this is not json", "[1, 2]", "\"a string\"", "{\"slug\": ", "null")) {
            assertEquals(text, MealieEntry.Unreadable, MealieRecipeParser.parse(text))
        }
        assertEquals(MealieEntry.Unreadable, MealieRecipeParser.parse("[".repeat(100_000)))
    }

    @Test fun `a byte order mark is ignored`() {
        assertTrue(MealieRecipeParser.parse("﻿" + snake) is MealieEntry.Recipe)
    }

    @Test fun `JSON nulls are absent, never the text null`() {
        val r = recipe(
            """{"slug": "s", "name": "Soup", "id": null, "recipe_yield": null, "prep_time": null, "org_url": null, "notes": null, "tags": null,
                "recipe_ingredient": [{"original_text": null, "display": null, "note": null, "food": null, "title": null, "reference_id": null},
                                      {"original_text": "1 egg", "reference_id": "a"}], "recipe_instructions": null}"""
        )
        assertEquals(listOf("1 egg"), r.ingredients)
        assertNull(r.id)
        assertNull(r.servings)
        assertNull(r.prepTime)
        assertNull(r.sourceUrl)
        assertNull(r.notes)
        assertTrue(r.categories.isEmpty())
    }
}
