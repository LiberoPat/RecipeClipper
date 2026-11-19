package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.remote.SharedText.Kind
import com.example.recipeclipper.data.remote.SharedText.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where text shared in goes (#306, the rules of #334), and what the editor opens with. */
class SharedTextTest {

    private val recipe = "Grandma's scones\n\nIngredients:\n- 2 cups flour\n- 1 tsp salt\n\n" +
        "Method:\n1. Mix the flour and salt.\n2. Bake for 20 minutes."

    @Test fun `a recipe splits and opens sorted, in the text's language, with the story left over`() {
        assertEquals(Kind.RECIPE, SharedText.kind(recipe))
        val reading = (SharedText.route(recipe) as Route.Editor).reading
        assertTrue(reading.sorted)
        assertEquals(listOf("2 cups flour", "1 tsp salt"), reading.recipe.ingredients)
        assertEquals(listOf("Mix the flour and salt.", "Bake for 20 minutes."), reading.recipe.instructions)
        assertEquals("en", reading.recipe.language)
        assertEquals(listOf("Grandma's scones"), reading.leftovers)
    }

    @Test fun `a recipe wins over a link in it, which is left over`() {
        val text = "$recipe\n\nSent from https://example.com/signature"
        assertEquals(Kind.RECIPE, SharedText.kind(text))
        assertEquals(
            listOf("Grandma's scones", "Sent from https://example.com/signature"),
            (SharedText.route(text) as Route.Editor).reading.leftovers
        )
    }

    @Test fun `a link wins only when the text is essentially just the link`() {
        assertEquals(Route.Link("https://example.com/scones"), SharedText.route("Look at this! https://example.com/scones"))
        assertEquals(Route.Link("https://example.com/scones"), SharedText.route("https://example.com/scones"))
        assertEquals(Kind.LINK, SharedText.kind("Scones\nThe best\nhttps://example.com/scones"))
        // Three other lines, or a long one, are the text the cook chose: the editor, as written.
        assertEquals(Kind.TEXT, SharedText.kind("Scones\nThe best\nReally\nhttps://example.com/scones"))
        assertEquals(Kind.TEXT, SharedText.kind("x".repeat(SharedText.CAPTION_LENGTH + 1) + "\nhttps://example.com/scones"))
        assertEquals(Kind.LINK, SharedText.kind("x".repeat(SharedText.CAPTION_LENGTH) + "\nhttps://example.com/scones"))
    }

    @Test fun `prose with a link opens the editor, not the link`() {
        val text = "Hi Mum,\nMix two cups of flour with the butter, then bake it until golden.\nLove, Sam\n" +
            "Sent from https://example.com/x"
        val route = SharedText.route(text) as Route.Editor
        assertFalse(route.reading.sorted)
        assertEquals(text.split("\n"), route.reading.recipe.instructions)
        // One sentence and a signature is a caption's worth: the link.
        assertEquals(Kind.LINK, SharedText.kind("Mix the flour with the butter and bake it.\nSent from https://example.com/x"))
    }

    @Test fun `a list is every line bulleted, headings allowed`() {
        assertEquals(Route.List, SharedText.route("Groceries\n- 2 lb chicken thighs\n- milk"))
        assertEquals(Route.List, SharedText.route("Groceries\n\nProduce\n- 1 lemon\n\nDairy\n- milk"))
        assertEquals(Route.List, SharedText.route("- 2 cups flour\n- 1 tsp salt"))
        // A bulleted line beside prose is a recipe for the editor, with nothing dropped.
        val mixed = "Here's grandma's scones…\n- 2 cups flour\n- 1 tsp salt\nMix everything and bake 20 minutes."
        val route = SharedText.route(mixed) as Route.Editor
        assertFalse(route.reading.sorted)
        assertEquals(4, route.reading.recipe.instructions.size)
        assertEquals(Kind.TEXT, SharedText.kind("Groceries\n- milk\n- eggs\nAnd get some bread."))
    }

    @Test fun `prose opens the editor as written, every line a step`() {
        val text = "Hi Mum,\n  Mix two cups of flour with the butter, then bake it.  \n\nLove, Sam"
        assertEquals(Kind.TEXT, SharedText.kind(text))
        val reading = SharedText.read(text)
        assertFalse(reading.sorted)
        assertEquals(emptyList<String>(), reading.recipe.ingredients)
        assertEquals(
            listOf("Hi Mum,", "Mix two cups of flour with the butter, then bake it.", "Love, Sam"),
            reading.recipe.instructions
        )
        assertNull(reading.recipe.yield)
        assertEquals(Kind.TEXT, SharedText.kind("milk\neggs\nbread"))
    }

    @Test fun `blank text goes nowhere`() {
        assertEquals(Route.None, SharedText.route("   \n   "))
        assertEquals(Route.None, SharedText.route(""))
    }

    @Test fun `text past the cap is never offered to the editor`() {
        val long = recipe + "\n" + "x".repeat(SharedText.MAX_LENGTH)
        assertEquals(Route.None, SharedText.route(long))
        assertEquals(Route.Link("https://example.com/a"), SharedText.route("$long\nhttps://example.com/a"))
        assertEquals(Route.List, SharedText.route("- milk\n- " + "x".repeat(SharedText.MAX_LENGTH)))
    }

    @Test fun `other line separators are line breaks`() {
        val text = "Ingredients: - 2 cups flour\u000B- 1 tsp salt Method:\u000C1. Mix.\u00852. Bake.\r3. Cool."
        val reading = (SharedText.route(text) as Route.Editor).reading
        assertTrue(reading.sorted)
        assertEquals(listOf("2 cups flour", "1 tsp salt"), reading.recipe.ingredients)
        assertEquals(listOf("Mix.", "Bake.", "Cool."), reading.recipe.instructions)
        assertEquals(Route.List, SharedText.route("Groceries - milk - eggs"))
    }

    @Test fun `plain text is never HTML, and dropped lines are left over as written`() {
        val text = "Ingredients:\n2 eggs <room temperature>\n1 cup sugar &amp; salt\nMethod:\n1. Beat.\n2. Add.\n" +
            "Notes:\nChill before serving.\nTip: use a hand mixer."
        val reading = SharedText.read(text)
        assertEquals(listOf("2 eggs <room temperature>", "1 cup sugar &amp; salt"), reading.recipe.ingredients)
        assertEquals(listOf("Notes:", "Chill before serving.", "Tip: use a hand mixer."), reading.leftovers)
    }

    @Test fun `the app's own shared text comes back sorted, with its yield and times, in any UI language`() {
        val english = "Grandma's scones\n\nServes 4\nPrep 10m · Cook 20m · Total 30m\n\nINGREDIENTS\n2 cups flour\n" +
            "1 tsp salt\n\nINSTRUCTIONS\n1. Mix the flour and salt.\n2. Bake for 20 minutes."
        val en = SharedText.read(english)
        assertTrue(en.sorted)
        assertEquals(listOf("2 cups flour", "1 tsp salt"), en.recipe.ingredients)
        assertEquals(listOf("Mix the flour and salt.", "Bake for 20 minutes."), en.recipe.instructions)
        assertEquals("Serves 4", en.recipe.yield)
        assertEquals(listOf("10m", "20m", "30m"), listOf(en.recipe.prepTime, en.recipe.cookTime, en.recipe.totalTime))
        assertEquals(listOf("Grandma's scones"), en.leftovers)
        assertEquals("en", en.recipe.language)

        // A German phone's words around an English recipe: the headings are read, the words stay English.
        val german = "Grandma's scones\n\nPortionen: 3 (ursprünglich 6)\nVorbereitung 10m · Garzeit 20m\n\nZUTATEN\n" +
            "1 cup flour\n½ tsp salt\n1 stick butter\n\nZUBEREITUNG\n1. Mix the flour and salt, then rub in the butter.\n2. Bake."
        val de = SharedText.read(german)
        assertTrue(de.sorted)
        assertEquals(listOf("1 cup flour", "½ tsp salt", "1 stick butter"), de.recipe.ingredients)
        assertEquals(listOf("Mix the flour and salt, then rub in the butter.", "Bake."), de.recipe.instructions)
        assertEquals("Portionen: 3 (ursprünglich 6)", de.recipe.yield)
        assertEquals(listOf("10m", "20m", null), listOf(de.recipe.prepTime, de.recipe.cookTime, de.recipe.totalTime))
        assertEquals("en", de.recipe.language)
    }

    @Test fun `German text is read with German words`() {
        val text = "Zutaten:\n- 500 g Mehl\n- 2 EL Zucker\n\nZubereitung:\n1. Mehl und Zucker mischen.\n2. Backen."
        assertEquals(Kind.RECIPE, SharedText.kind(text))
        assertEquals("de", SharedText.read(text).recipe.language)
    }

    // --- A schema.org Recipe as JSON, as an AI chat writes it (#350) ---

    private val json = """
        {"@context": "https://schema.org", "@type": "Recipe", "name": "Lemon Pasta",
         "url": "https://made-up.example/lemon-pasta", "image": "https://made-up.example/p.jpg",
         "recipeYield": "4 servings", "prepTime": "PT10M", "cookTime": "PT15M", "totalTime": "PT25M",
         "recipeIngredient": ["200 g spaghetti", "1 lemon", "2 tbsp butter"],
         "recipeInstructions": [
           {"@type": "HowToStep", "text": "Boil the pasta."},
           {"@type": "HowToStep", "text": "Toss with butter and lemon."}]}
    """.trimIndent()

    private fun assertLemonPasta(text: String) {
        assertEquals(Kind.RECIPE, SharedText.kind(text))
        val reading = (SharedText.route(text) as Route.Editor).reading
        assertTrue(reading.sorted)
        assertEquals("Lemon Pasta", reading.name)
        assertEquals(listOf("200 g spaghetti", "1 lemon", "2 tbsp butter"), reading.recipe.ingredients)
        assertEquals(listOf("Boil the pasta.", "Toss with butter and lemon."), reading.recipe.instructions)
        assertEquals("4 servings", reading.recipe.yield)
        assertEquals(listOf("10m", "15m", "25m"), listOf(reading.recipe.prepTime, reading.recipe.cookTime, reading.recipe.totalTime))
        assertTrue(reading.leftovers.isEmpty())
    }

    @Test fun `bare schema org JSON opens sorted with its name, times and yield`() = assertLemonPasta(json)

    @Test fun `JSON in a fence, with prose around it, is read the same`() {
        assertLemonPasta("```json\n$json\n```")
        assertLemonPasta("Sure! Here's a Recipe for you:\n\n```json\n$json\n```\n\nEnjoy [with a salad]! {Serves 4}")
        assertLemonPasta("Here it is: $json Hope that helps.")
        assertLemonPasta(json.replace("\n", "\r\n"))
    }

    @Test fun `a Recipe in a graph, an array of recipes and a section's steps are read as for a page`() {
        val graph = """{"@context":"https://schema.org","@graph":[{"@type":"WebPage","name":"x"},
            {"@type":["Thing","Recipe"],"name":"Soup","recipeIngredient":["1 onion"],"recipeInstructions":
            [{"@type":"HowToSection","name":"Base","itemListElement":[{"@type":"HowToStep","text":"Fry."}]},"Simmer."]}]}"""
        val soup = SharedText.read("Your recipe:\n$graph").also { assertTrue(it.sorted) }
        assertEquals("Soup", soup.name)
        assertEquals(listOf("1 onion"), soup.recipe.ingredients)
        assertEquals(listOf("Fry.", "Simmer."), soup.recipe.instructions)

        val two = """[{"@type":"Recipe","name":"First","recipeIngredient":["1 egg"]},
            {"@type":"Recipe","name":"Second","recipeIngredient":["2 eggs"]}]"""
        assertEquals("First", SharedText.read(two).name)
    }

    @Test fun `a url in the JSON is not what the text is`() {
        assertEquals(Kind.RECIPE, SharedText.kind("$json\nhttps://made-up.example/lemon-pasta"))
        assertEquals(Kind.LINK, SharedText.kind("https://made-up.example/lemon-pasta"))
    }

    @Test fun `JSON that is broken or isn't a recipe falls through to today's rules`() {
        val broken = json.dropLast(40)
        assertFalse(SharedText.read(broken).sorted)
        assertEquals("", SharedText.read(broken).name)
        val notRecipe = """{"@type": "Person", "name": "Ann", "note": "recipe for disaster"}"""
        assertEquals(Kind.TEXT, SharedText.kind(notRecipe))
        // A Recipe with a name and nothing to cook is not one.
        assertEquals(Kind.TEXT, SharedText.kind("""{"@type":"Recipe","name":"Empty"}"""))
        // A plain-text recipe with brackets in it still splits as before.
        assertEquals(Kind.RECIPE, SharedText.kind(recipe.replace("Grandma's", "Grandma's [best] {recipe}")))
    }

    @Test fun `JSON nested far too deep is no recipe and never a crash`() {
        assertFalse(SharedText.read("recipe " + "[".repeat(20_000) + "]".repeat(20_000)).sorted)
        assertFalse(SharedText.read("recipe " + "{\"a\":".repeat(5_000) + "1" + "}".repeat(5_000)).sorted)
        assertFalse(SharedText.read("recipe " + "[".repeat(30_000)).sorted)
    }
}
