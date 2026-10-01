package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The iOS suite (`RedditRecipeParserTests.swift`) has the same cases and expectations. */
class RedditRecipeParserTest {

    private val url = "https://www.reddit.com/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo/"

    private fun recipe(json: String): Recipe =
        (RedditRecipeParser.parse(json, url) as ParseResult.Success).recipe

    @Test fun `a self post's body is the recipe`() {
        assertEquals(
            Recipe(
                name = "Weeknight Lemon Chicken Orzo",
                image = "https://preview.redd.it/orzo7kq2.jpeg?width=1080&format=pjpg&auto=webp&s=1a2b3c",
                ingredients = listOf(
                    "1 lb chicken thighs", "1 cup orzo", "2 1/2 cups chicken broth", "1 lemon, zested and juiced"
                ),
                instructions = listOf(
                    "Brown the chicken in a deep pan, 6 minutes a side.",
                    "Add the orzo and broth and simmer for 12 minutes.",
                    "Stir in the lemon & serve."
                ),
                prepTime = "10m",
                cookTime = "25m",
                totalTime = null,
                yield = null,
                sourceUrl = url,
                sourceType = SourceType.REDDIT,
                language = "en"
            ),
            recipe(RedditFixtures.SELF_POST)
        )
    }

    @Test fun `a photo post takes the recipe from the poster's own comment`() {
        // AutoModerator's template splits too, and another reader's recipe comes first: the
        // poster's comment wins over both. The blank "&#x200B;" and the blog link are dropped.
        val r = recipe(RedditFixtures.IMAGE_WITH_OP_RECIPE)
        assertEquals("Sticky Honey Garlic Chicken Thighs", r.name)
        assertEquals("https://preview.redd.it/k2m8x7vq1abd1.jpeg?auto=webp&s=5c1e0f1a2b3c4d5e6f", r.image)
        assertEquals(
            listOf(
                "2 lb (900 g) boneless chicken thighs", "1 tsp salt", "1 tbsp oil",
                "For the sauce:", "1/3 cup honey", "1/4 cup soy sauce", "6 cloves garlic, minced"
            ),
            r.ingredients
        )
        assertEquals(
            listOf(
                "Pat the chicken dry and season with the salt.",
                "Sear in the oil over medium-high heat, 5 minutes a side.",
                "Whisk the sauce, pour it in and simmer until sticky, about 8 minutes."
            ),
            r.instructions
        )
        assertEquals("Serves 4", r.yield)
        assertEquals(SourceType.REDDIT, r.sourceType)
    }

    @Test fun `a gallery's recipe card takes its recipe from a transcription under AutoModerator`() {
        val r = recipe(RedditFixtures.CARD_WITH_TRANSCRIPTION)
        assertEquals("Grandma's date nut bread, found in her recipe tin", r.name)
        assertEquals("https://preview.redd.it/a1card0front.jpg?width=3024&format=pjpg&auto=webp&s=1a", r.image)
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
    }

    @Test fun `a reply whose ingredients have no header is still a recipe`() {
        val r = recipe(RedditFixtures.REPLY_WITHOUT_HEADERS)
        assertEquals("[Request] Spinach dip for two people", r.name)
        assertNull(r.image)
        assertEquals(
            listOf(
                "2.5 oz. frozen spinach, thawed and squeezed dry", "1/4 cup minced onion", "1/2 teaspoon minced garlic",
                "1/8 cup sour cream", "1/8 cup mayonnaise", "Salt – 1/8 tsp"
            ),
            r.ingredients
        )
        assertEquals(listOf("Stir everything together in a small bowl and chill for an hour before serving."), r.instructions)
    }

    @Test fun `only chatter is an honest outcome with the post's photo`() {
        assertEquals(
            ParseResult.Error(
                ParseError.NoTranscription(
                    title = "[Homemade] Sunday lasagna",
                    imageUrl = "https://preview.redd.it/lasagna4x9.jpeg?auto=webp&s=4d5e6f"
                )
            ),
            RedditRecipeParser.parse(RedditFixtures.PHOTO_ONLY, url)
        )
        assertNull(RedditRecipeParser.read(RedditFixtures.PHOTO_ONLY, url).crosspostOf)
    }

    @Test fun `an untranscribed gallery carries every picture, in the gallery's order, for reading (#198)`() {
        val error = (RedditRecipeParser.parse(RedditFixtures.CARD_UNTRANSCRIBED, url) as ParseResult.Error).error
        assertEquals(
            ParseError.NoTranscription(
                title = "Aunt June's oatmeal cookies, front and back of the card",
                imageUrl = "https://preview.redd.it/c3card1front.jpg?width=3024&format=pjpg&auto=webp&s=c32",
                imageUrls = listOf(
                    "https://preview.redd.it/c3card1front.jpg?width=3024&format=pjpg&auto=webp&s=c32",
                    "https://preview.redd.it/d4card1back.jpg?width=3024&format=pjpg&auto=webp&s=d42"
                )
            ),
            error
        )
    }

    @Test fun `a crosspost with no recipe of its own names the original for the source`() {
        val reading = RedditRecipeParser.read(RedditFixtures.CROSSPOST, url)
        assertEquals("1f3k9xq", reading.crosspostOf)
        assertEquals(
            ParseResult.Error(
                ParseError.NoTranscription(
                    title = "Saw this on r/recipes and had to share",
                    imageUrl = "https://preview.redd.it/k2m8x7vq1abd1.jpeg?auto=webp&s=5c1e0f1a2b3c4d5e6f"
                )
            ),
            reading.result
        )
    }

    @Test fun `a crosspost borrows the original's body and photo`() {
        val json = """[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{
            "subreddit":"Cooking","title":"Crossposting this great soup","selftext":"","crosspost_parent":"t3_1abc04",
            "crosspost_parent_list":[{"subreddit":"soup","title":"Tomato soup","is_self":true,
              "selftext":"## Ingredients\n\n* 2 lb tomatoes\n* 1 onion\n\n## Method\n\n* Roast everything.\n* Blend.",
              "preview":{"images":[{"source":{"url":"https://preview.redd.it/soup.jpeg?a=1&s=x"}}]}}]
            }}]}},{"kind":"Listing","data":{"children":[]}}]"""
        val r = recipe(json)
        assertEquals("Crossposting this great soup", r.name)
        assertEquals("https://preview.redd.it/soup.jpeg?a=1&s=x", r.image)
        assertEquals(listOf("2 lb tomatoes", "1 onion"), r.ingredients)
        assertEquals(listOf("Roast everything.", "Blend."), r.instructions)
    }

    @Test fun `the post body wins over a comment`() {
        val json = RedditFixtures.CARD_WITH_TRANSCRIPTION.replace(
            "\"selftext\": \"\"",
            "\"selftext\": \"Ingredients\\n1 cup figs\\nMethod\\nEat.\""
        )
        assertEquals(listOf("1 cup figs"), recipe(json).ingredients)
    }

    @Test fun `a removed body falls through to the comments`() {
        val json = RedditFixtures.CARD_WITH_TRANSCRIPTION.replace("\"selftext\": \"\"", "\"selftext\": \"[removed]\"")
        assertEquals("1 cup chopped dates", recipe(json).ingredients.first())
    }

    @Test fun `a link straight to an image is the photo when there is no preview, and amp is undone`() {
        val json = """[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"Pie",
            "selftext":"","url":"https://i.imgur.com/pie.png"}}]}},{"kind":"Listing","data":{"children":[]}}]"""
        assertEquals(ParseResult.Error(ParseError.NoTranscription("Pie", "https://i.imgur.com/pie.png")),
            RedditRecipeParser.parse(json, url))
        val noImage = json.replace("https://i.imgur.com/pie.png", "https://example.com/pie-recipe")
        assertEquals(ParseResult.Error(ParseError.NoTranscription("Pie", null)), RedditRecipeParser.parse(noImage, url))
        // Without raw_json Reddit escapes the preview's "&".
        val escaped = json.replace("\"selftext\"", "\"preview\":{\"images\":[{\"source\":{\"url\":\"https://preview.redd.it/p.jpg?a=1&amp;s=2\"}}]},\"selftext\"")
        assertEquals(ParseResult.Error(ParseError.NoTranscription("Pie", "https://preview.redd.it/p.jpg?a=1&s=2")),
            RedditRecipeParser.parse(escaped, url))
    }

    @Test fun `the post's picture is a web image, http upgraded (#235)`() {
        val json = """[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"Pie",
            "selftext":"","url":"http://i.imgur.com/pie.png"}}]}},{"kind":"Listing","data":{"children":[]}}]"""
        assertEquals(ParseResult.Error(ParseError.NoTranscription("Pie", "https://i.imgur.com/pie.png")),
            RedditRecipeParser.parse(json, url))
        val local = json.replace("http://i.imgur.com/pie.png", "file:///sdcard/pie.png")
        assertEquals(ParseResult.Error(ParseError.NoTranscription("Pie", null)), RedditRecipeParser.parse(local, url))
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

    @Test fun `comments are walked depth first in Reddit's order, without more stubs or AutoModerator`() {
        val image = RedditRecipeParser.comments(org.json.JSONArray(RedditFixtures.IMAGE_WITH_OP_RECIPE).getJSONObject(1))
        assertEquals(listOf(false, true, true), image.map { it.bySubmitter })
        assert(image[0].body.startsWith("Looks great."))
        assertEquals("Ha, that works too on a busy night!", image[1].body)
        assert(image[2].body.startsWith("Thanks for looking!"))

        // AutoModerator's own comment is skipped, the replies under it are not.
        val card = RedditRecipeParser.comments(org.json.JSONArray(RedditFixtures.CARD_WITH_TRANSCRIPTION).getJSONObject(1))
        assertEquals(3, card.size)
        assert(card[0].body.startsWith("Transcription:"))
        assertEquals(listOf(false, true, false), card.map { it.bySubmitter })
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
