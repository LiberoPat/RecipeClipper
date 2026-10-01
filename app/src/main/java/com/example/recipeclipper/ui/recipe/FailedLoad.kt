package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.AppInfo
import com.example.recipeclipper.data.PhotoPost
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.SiteReportLink
import com.example.recipeclipper.data.remote.RedditUrls

/**
 * Where a load that failed goes (#30, #37, #198, #213, #220, #234). Reddit's wall opens the post
 * in "Clip it yourself" in the screen's place, Cloudflare's check opens the page for the cook to
 * pass, and anything else is the error screen with what it offers. [shareUrl] is the shared link,
 * null for a recipe opened by id; [redditOn] is the `reddit` flag (#11), read at each load.
 */
class FailedLoad(
    private val shareUrl: String?,
    private val appInfo: AppInfo,
    private val redditOn: () -> Boolean
) {

    /** [state] with the load's [error] shown, or handed on in the screen's place. */
    fun shown(state: RecipeUiState, error: ParseError): RecipeUiState {
        // Reddit wouldn't let the app read the post (#213): no error screen; the post opens
        // in "Clip it yourself" instead, where Reddit does let it in.
        val clipInstead = shareUrl?.takeIf { RedditUrls.clipsWhenBlocked(it, error, redditOn()) }
        // Cloudflare's check wants a person (#220): no error screen; the page opens visibly
        // for the cook to pass it.
        val checkInstead = shareUrl?.takeIf { error == ParseError.HumanCheck }
        return if (clipInstead != null) {
            state.copy(clipBlockedPost = clipInstead)
        } else if (checkInstead != null) {
            state.copy(humanCheckPage = checkInstead)
        } else {
            state.copy(
                content = RecipeContent.Error(error),
                reportSiteUrl = reportSiteUrl(error),
                clipUrl = shareUrl.takeIf { error == ParseError.NoRecipeFound },
                photoPost = photoPost(error)
            )
        }
    }

    /** A shared Reddit post with no recipe text but a picture can have its photo read (#198). */
    private fun photoPost(error: ParseError): PhotoPost? {
        val post = error as? ParseError.NoTranscription ?: return null
        val link = shareUrl ?: return null
        return PhotoPost(link, post.title, post.imageUrls).takeIf { it.imageUrls.isNotEmpty() }
    }

    /**
     * Only a shared link that loaded but held no recipe is worth reporting: a block, being
     * offline or a failed fetch usually lifts on its own, and a saved recipe has no page to
     * report.
     */
    private fun reportSiteUrl(error: ParseError): String? {
        if (error != ParseError.NoRecipeFound) return null
        val link = shareUrl ?: return null
        return SiteReportLink.issueUrl(link, appInfo.platform, appInfo.appVersion)
    }
}
