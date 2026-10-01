package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.PhotoPost
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.SiteReportLink
import com.example.recipeclipper.fake.FakeAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [FailedLoad] on its own (#234): where a load that failed goes. */
class FailedLoadTest {

    private val appInfo = FakeAppInfo()
    private val link = "https://example.com/cake"
    private val post = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/"

    private fun shared(url: String, redditOn: Boolean = true) = FailedLoad(url, appInfo, redditOn = { redditOn })

    @Test fun `a shared page with no recipe offers the clip and the report`() {
        val shown = shared(link).shown(RecipeUiState(), ParseError.NoRecipeFound)

        assertEquals(RecipeContent.Error(ParseError.NoRecipeFound), shown.content)
        assertEquals(link, shown.clipUrl)
        assertEquals(SiteReportLink.issueUrl(link, appInfo.platform, appInfo.appVersion), shown.reportSiteUrl)
    }

    @Test fun `a recipe opened by id has no page to clip or report`() {
        val shown = FailedLoad(null, appInfo, redditOn = { true }).shown(RecipeUiState(), ParseError.NoRecipeFound)

        assertEquals(RecipeContent.Error(ParseError.NoRecipeFound), shown.content)
        assertNull(shown.clipUrl)
        assertNull(shown.reportSiteUrl)
    }

    @Test fun `a block that usually lifts offers neither`() {
        val shown = shared(link).shown(RecipeUiState(), ParseError.Blocked(403))

        assertEquals(RecipeContent.Error(ParseError.Blocked(403)), shown.content)
        assertNull(shown.clipUrl)
        assertNull(shown.reportSiteUrl)
    }

    @Test fun `a walled Reddit post opens the clip in the screen's place`() {
        val shown = shared(post).shown(RecipeUiState(), ParseError.Blocked(403))

        assertEquals(post, shown.clipBlockedPost)
        assertEquals(RecipeContent.Loading, shown.content)
    }

    @Test fun `with the reddit flag off a walled post is an error like any other`() {
        val shown = shared(post, redditOn = false).shown(RecipeUiState(), ParseError.Blocked(403))

        assertNull(shown.clipBlockedPost)
        assertEquals(RecipeContent.Error(ParseError.Blocked(403)), shown.content)
    }

    @Test fun `Cloudflare's check opens the page for the cook to pass`() {
        val shown = shared(link).shown(RecipeUiState(), ParseError.HumanCheck)

        assertEquals(link, shown.humanCheckPage)
        assertEquals(RecipeContent.Loading, shown.content)
    }

    @Test fun `a post with no recipe text but a photo can have its photo read`() {
        val error = ParseError.NoTranscription("Lemon orzo", "https://i.redd.it/a.jpg")

        val shown = shared(post).shown(RecipeUiState(), error)

        assertEquals(PhotoPost(post, "Lemon orzo", listOf("https://i.redd.it/a.jpg")), shown.photoPost)
        assertNull(shared(post).shown(RecipeUiState(), ParseError.NoTranscription("Lemon orzo", null)).photoPost)
    }
}
