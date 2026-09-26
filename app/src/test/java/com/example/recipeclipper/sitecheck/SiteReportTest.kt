package com.example.recipeclipper.sitecheck

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weekly site check's report, offline, so the normal run catches a broken harness before
 *  the scheduled one does. The URL list is checked too: it ships in the test resources. */
class SiteReportTest {

    private val recipe = Recipe(
        name = "Soup", image = "https://img.example/a.jpg", ingredients = listOf("1 egg", "2 cups water"),
        instructions = listOf("Boil."), prepTime = null, cookTime = null, totalTime = "20m",
        yield = null, sourceUrl = "https://www.soup.example/soup"
    )

    @Test fun `the URL list skips comments and blanks`() =
        assertEquals(
            listOf("https://a.example/1", "https://b.example/2"),
            SiteReport.parseUrlList("# header\n\nhttps://a.example/1\n  https://b.example/2  # note\n")
        )

    @Test fun `the checked-in URL list is well formed and has no duplicates`() {
        val text = checkNotNull(javaClass.getResource("/site-check-urls.txt")).readText()
        val urls = SiteReport.parseUrlList(text)
        assertTrue(urls.size >= 15)
        urls.forEach { assertTrue(it, it.startsWith("https://") && ' ' !in it) }
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test fun `causes are named as in the code`() {
        assertEquals("Blocked(403)", SiteReport.describe(ParseError.Blocked(403)))
        assertEquals("FetchFailed(HTTP 400)", SiteReport.describe(ParseError.FetchFailed("HTTP 400")))
        assertEquals("FetchFailed(timed out)", SiteReport.describe(ParseError.FetchFailed("x", timedOut = true)))
        assertEquals("NoRecipeFound", SiteReport.describe(ParseError.NoRecipeFound))
        assertEquals("Offline", SiteReport.describe(ParseError.Offline))
    }

    @Test fun `the table has one row per URL with shape for a success and the cause for a failure`() {
        val md = SiteReport.markdown(
            listOf(
                SiteReport.Outcome(recipe.sourceUrl, ParseResult.Success(recipe), ParseError.Blocked(429), 2500),
                SiteReport.Outcome("https://b.example/x", ParseResult.Error(ParseError.FetchFailed("bad | pipe\nline"))),
            ),
            "2026-09-23T00:00:00Z"
        )
        assertTrue(md, "1 of 2 parsed" in md)
        assertTrue(md, "| [soup.example](https://www.soup.example/soup) | Parsed (retried after Blocked(429)) | | 2 | 1 | no | yes | yes | 2.5 s |" in md)
        assertTrue(md, "| Failed | FetchFailed(bad \\| pipe line) |" in md)
    }

    @Test fun `a site rule that stopped matching is flagged, in the table and the JSON`() {
        val outcomes = listOf(
            SiteReport.Outcome(recipe.sourceUrl, ParseResult.Success(recipe), rules = mapOf("ingredients" to true, "step noise" to false)),
            SiteReport.Outcome("https://b.example/x", ParseResult.Success(recipe)),
        )
        val md = SiteReport.markdown(outcomes, "t")
        assertTrue(md, "| soup.example | ingredients | Matched | 1 of 1 |" in md)
        assertTrue(md, "| soup.example | step noise | **Stopped matching** | 0 of 1 |" in md)
        assertTrue(md, "b.example |" !in md.substringAfter("### Site rules"))
        val entry = JSONObject(SiteReport.json(outcomes, "t")).getJSONArray("results").getJSONObject(0)
        assertEquals(false, entry.getJSONObject("siteRules").getBoolean("step noise"))
        assertTrue("### Site rules" !in SiteReport.markdown(outcomes.drop(1), "t"))
    }

    @Test fun `a site rule is judged over all of its site's pages`() {
        // Not every page has an editor's note, or groups to head: one page of the site is enough.
        val outcomes = listOf(
            SiteReport.Outcome(recipe.sourceUrl, ParseResult.Success(recipe), rules = mapOf("ingredients" to true, "step noise" to false)),
            SiteReport.Outcome("https://soup.example/stew", ParseResult.Success(recipe), rules = mapOf("ingredients" to false, "step noise" to true)),
        )
        val md = SiteReport.markdown(outcomes, "t")
        assertTrue(md, "| soup.example | ingredients | Matched | 1 of 2 |" in md)
        assertTrue(md, "| soup.example | step noise | Matched | 1 of 2 |" in md)
        assertTrue(md, "Stopped matching**" !in md)
    }

    @Test fun `the JSON records outcomes only, never recipe text`() {
        val json = SiteReport.json(listOf(SiteReport.Outcome(recipe.sourceUrl, ParseResult.Success(recipe))), "t")
        val entry = JSONObject(json).getJSONArray("results").getJSONObject(0)
        assertEquals("parsed", entry.getString("outcome"))
        assertEquals(2, entry.getInt("ingredients"))
        assertTrue(json, "egg" !in json && "Boil" !in json && "Soup" !in json)
    }
}
