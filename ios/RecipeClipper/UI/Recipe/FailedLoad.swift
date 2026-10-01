import Foundation

/// Where a load that failed goes (#30, #37, #198, #213, #220, #234; Android's `FailedLoad`).
/// Reddit's wall opens the post in "Clip it yourself" in the screen's place, Cloudflare's check
/// opens the page for the cook to pass, and anything else is the error screen with what it offers.
/// `shareUrl` is the shared link, nil for a recipe opened by id; `redditOn` is the `reddit` flag
/// (#11), read at each load.
struct FailedLoad {
    let shareUrl: String?
    let appInfo: AppInfo
    let redditOn: () -> Bool

    /// `state` with the load's `error` shown, or handed on in the screen's place.
    func shown(_ state: RecipeUiState, _ error: ParseError) -> RecipeUiState {
        var next = state
        // Reddit wouldn't let the app read the post (#213): no error screen; the post opens in
        // "Clip it yourself" instead, where Reddit does let it in.
        if let shareUrl, RedditUrls.clipsWhenBlocked(shareUrl, error: error, redditOn: redditOn()) {
            next.clipBlockedPost = shareUrl
            return next
        }
        // Cloudflare's check wants a person (#220): no error screen; the page opens visibly for
        // the cook to pass it.
        if let shareUrl, error == .humanCheck {
            next.humanCheckPage = shareUrl
            return next
        }
        next.content = .error(error)
        next.reportSiteUrl = reportSiteUrl(for: error)
        next.clipUrl = error == .noRecipeFound ? shareUrl : nil
        next.photoPost = photoPost(for: error)
        return next
    }

    /// A shared Reddit post with no recipe text but a picture can have its photo read (#198).
    private func photoPost(for error: ParseError) -> PhotoPost? {
        guard case .noTranscription(let title, _, _) = error, let shareUrl,
              let images = error.photoUrls, !images.isEmpty else { return nil }
        return PhotoPost(url: shareUrl, title: title, imageUrls: images)
    }

    /// Only a shared link that loaded but held no recipe is worth reporting: a block, being
    /// offline or a failed fetch usually lifts on its own, and a saved recipe has no page to
    /// report.
    private func reportSiteUrl(for error: ParseError) -> String? {
        guard error == .noRecipeFound, let shareUrl else { return nil }
        return SiteReportLink.issueUrl(link: shareUrl, platform: appInfo.platform, appVersion: appInfo.appVersion)
    }
}
