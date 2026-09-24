package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The iOS suite (`RedditRecipeParserTests.swift`) has the same cases and expectations. */
class RedditRecipeParserTest {

    private val url = "https://www.reddit.com/r/recipes/comments/1abc01/weeknight_lemon_chicken_orzo/"

    private fun recipe(json: String): Recipe =
        (RedditRecipeParser.parse(json, url) as ParseResult.Success).recipe

    @Test fun `a self post's body is the recipe`() {
        assertEquals(
            Recipe(
                name = "Weeknight Lemon Chicken Orzo",
                image = "https://preview.redd.it/orzo.jpeg?width=1080&format=pjpg&s=abc",
                ingredients = listOf(
                    "1 lb chicken thighs", "1 cup orzo", "2 1/2 cups chicken broth", "1 lemon, zested and juiced"
                ),
                instructions = listOf(
                    "Brown the chicken in a deep pan, 6 minutes a side.",
                    "Add the orzo and broth and simmer for 12 minutes.",
                    "Stir in the lemon and serve."
                ),
                prepTime = "10m",
                cookTime = "25m",
                totalTime = null,
                yield = "Serves 4",
                sourceUrl = url,
                sourceType = SourceType.REDDIT
            ),
            recipe(RedditFixtures.SELF_POST)
        )
    }

    @Test fun `a photo post takes its recipe from the transcription in the comments`() {
        val r = recipe(RedditFixtures.CARD_WITH_TRANSCRIPTION)
        assertEquals("Grandma's date nut bread, found in her tin", r.name)
        assertEquals("https://preview.redd.it/card01.jpeg?auto=webp&s=def", r.image)
        assertEquals(
            listOf("1 cup chopped dates", "1 tsp baking soda", "1 cup boiling water", "1 3/4 cups flour", "1/2 cup chopped walnuts"),
            r.ingredients
        )
        assertEquals(
            listOf(
                "Pour the boiling water over the dates and soda and let cool.",
                "Stir in the flour and nuts.",
                "Bake in a greased loaf pan at 350°F for 1 hour."
            ),
            r.instructions
        )
        assertEquals(SourceType.REDDIT, r.sourceType)
        assertEquals(url, r.sourceUrl)
    }

    @Test fun `no transcription anywhere is an honest outcome with the post's photo`() {
        assertEquals(
            ParseResult.Error(
                ParseError.NoTranscription(
                    title = "[Homemade] Sunday lasagna",
                    imageUrl = "https://preview.redd.it/m1.jpg?width=800&s=1"
                )
            ),
            RedditRecipeParser.parse(RedditFixtures.PHOTO_ONLY, url)
        )
    }

    @Test fun `a crosspost borrows the original's body and photo`() {
        val r = recipe(RedditFixtures.CROSSPOST)
        assertEquals("Crossposting this great soup", r.name)
        assertEquals("https://preview.redd.it/soup.jpeg?a=1&s=x", r.image)
        assertEquals(listOf("2 lb tomatoes", "1 onion"), r.ingredients)
        assertEquals(listOf("Roast everything.", "Blend."), r.instructions)
    }

    @Test fun `the post body wins over a comment`() {
        val json = RedditFixtures.CARD_WITH_TRANSCRIPTION.replace(
            "\"selftext\":\"\"",
            "\"selftext\":\"Ingredients\\n1 cup figs\\nMethod\\nEat.\""
        )
        assertEquals(listOf("1 cup figs"), recipe(json).ingredients)
    }

    @Test fun `a removed body falls through to the comments`() {
        val json = RedditFixtures.CARD_WITH_TRANSCRIPTION.replace("\"selftext\":\"\"", "\"selftext\":\"[removed]\"")
        assertEquals("1 cup chopped dates", recipe(json).ingredients.first())
    }

    @Test fun `a link straight to an image is the photo when there is no preview`() {
        val json = """[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"Pie",
            "selftext":"","url":"https://i.imgur.com/pie.png"}}]}},{"kind":"Listing","data":{"children":[]}}]"""
        assertEquals(ParseResult.Error(ParseError.NoTranscription("Pie", "https://i.imgur.com/pie.png")),
            RedditRecipeParser.parse(json, url))
        val noImage = json.replace("https://i.imgur.com/pie.png", "https://example.com/pie-recipe")
        assertEquals(ParseResult.Error(ParseError.NoTranscription("Pie", null)), RedditRecipeParser.parse(noImage, url))
    }

    @Test fun `anything that isn't a post listing has no recipe`() {
        val none = ParseResult.Error(ParseError.NoRecipeFound)
        assertEquals(none, RedditRecipeParser.parse("<html>whoa there, pardner</html>", url))
        assertEquals(none, RedditRecipeParser.parse("""{"kind":"Listing","data":{"children":[]}}""", url))
        assertEquals(none, RedditRecipeParser.parse("[]", url))
        assertEquals(none, RedditRecipeParser.parse("""[{"kind":"Listing","data":{"children":[{"kind":"t5","data":{"title":"x"}}]}}]""", url))
        assertEquals(none, RedditRecipeParser.parse("""[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"  "}}]}}]""", url))
        assertEquals(none, RedditRecipeParser.parse("[".repeat(100_000), url))
    }

    @Test fun `comments are walked depth first in Reddit's order, skipping more stubs`() {
        val listing = org.json.JSONArray(RedditFixtures.CARD_WITH_TRANSCRIPTION).getJSONObject(1)
        val bodies = RedditRecipeParser.comments(listing)
        assertEquals(5, bodies.size)
        assertEquals("Her handwriting is beautiful.", bodies[1])
        assert(bodies[3].startsWith("Transcription:"))
        assert(bodies[4].startsWith("Ingredients:"))
    }

    @Test fun `an absurdly deep reply chain stops at the depth guard`() {
        var node = """{"kind":"Listing","data":{"children":[]}}"""
        repeat(80) {
            node = """{"kind":"Listing","data":{"children":[{"kind":"t1","data":{"body":"reply $it","replies":$node}}]}}"""
        }
        val bodies = RedditRecipeParser.comments(org.json.JSONObject(node))
        assertEquals(51, bodies.size)
    }

    @Test fun `no transcription is an outcome, never retried, never reloaded on reconnect`() {
        val error = ParseError.NoTranscription("Pie", null)
        assertEquals(false, error.shouldAutoRetry)
        assertEquals(false, error.reloadsOnReconnect)
    }
}
