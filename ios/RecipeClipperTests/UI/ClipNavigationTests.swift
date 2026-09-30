import XCTest
@testable import RecipeClipper

/// The same cases and expectations as Android's `ClipNavigationTest`.
final class ClipNavigationTests: XCTestCase {

    private let post = "https://www.reddit.com/r/recipes/comments/1abc01/apple_pie/"

    private func loads(
        _ target: String, current: String? = nil, isRedirect: Bool = false, tapped: Bool = true, withinSite: Bool = false
    ) -> Bool {
        ClipNavigation.loads(target, current: current ?? post, isRedirect: isRedirect, tapped: tapped, withinSite: withinSite)
    }

    func testRedditsCheckSendingThePageBackToItselfWithItsTokenLoads() {
        // Its script submits a hidden GET form to the post's own path once it has run (#213).
        XCTAssertTrue(loads("\(post)?solution=ab12ab12&js_challenge=1&jsc_token=f00d&jsc_orig_r=", tapped: false))
        // After a share link's redirect, the post's address carries the share query already.
        XCTAssertTrue(loads(
            "\(post)?share_id=x1&utm_source=share&solution=ab&js_challenge=1&jsc_token=f00d",
            current: "\(post)?share_id=x1&utm_source=share",
            tapped: false
        ))
    }

    func testATappedLinkToTheSamePathWithAnotherQueryDoesNotLoad() {
        XCTAssertFalse(loads("\(post)?sort=new", tapped: true))
    }

    func testAPageScriptMovingToAnotherPathOrSiteDoesNotLoad() {
        XCTAssertFalse(loads("https://www.reddit.com/r/recipes/", tapped: false))
        XCTAssertFalse(loads("https://ads.example/landing", tapped: false))
    }

    func testAnAppsOwnLinkNeverLoadsEvenAsARedirectOrWhileWaitingOnACheck() {
        for url in [
            "intent://r/recipes/comments/1abc01#Intent;scheme=reddit;package=com.reddit.frontpage;end",
            "reddit://reddit/r/recipes/comments/1abc01",
            "market://details?id=com.reddit.frontpage",
            "javascript:void(0)",
        ] {
            XCTAssertFalse(loads(url), url)
            XCTAssertFalse(loads(url, isRedirect: true), url)
            XCTAssertFalse(loads(url, tapped: false, withinSite: true), url)
        }
    }

    func testRedirectsAndFragmentJumpsLoadLinksToOtherPagesDoNot() {
        XCTAssertTrue(loads(post, current: "https://www.reddit.com/r/recipes/s/AbCd123", isRedirect: true))
        XCTAssertTrue(loads("\(post)#comments"))
        XCTAssertFalse(loads("https://www.reddit.com/r/recipes/comments/2def02/other/"))
        XCTAssertFalse(loads("https://hearthandcrumb.example/other", current: "https://hearthandcrumb.example/cookies"))
    }

    func testWaitingOnCloudflaresCheckAnythingOnThePagesSiteLoads() {
        let page = "https://hearthandcrumb.example/cookies"
        XCTAssertTrue(loads("https://hearthandcrumb.example/cookies?__cf_chl_tk=abc", current: page, withinSite: true))
        XCTAssertTrue(loads("https://hearthandcrumb.example/elsewhere", current: page, withinSite: true))
        XCTAssertFalse(loads("https://other.example/", current: page, withinSite: true))
    }
}
