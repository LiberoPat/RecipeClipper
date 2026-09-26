import XCTest
@testable import RecipeClipper

/// Port of Android's FirstRunTourTest: the first-run tour's rules (#151) over fakes.
@MainActor
final class FirstRunTourTests: XCTestCase {
    private var preferences: MemoryTourPreferences!
    private var recipes: FakeRecipeRepository!
    private var tour: FirstRunTour!

    override func setUp() {
        preferences = MemoryTourPreferences(welcome: .undecided, sampleAdded: false, seen: [])
        recipes = FakeRecipeRepository()
        tour = FirstRunTour(preferences: preferences, recipes: recipes)
    }

    private func summary(_ id: Int64) -> RecipeSummary {
        RecipeSummary(id: id, title: "Recipe \(id)", imageUrl: nil, totalTime: nil, lastViewedAt: id, isSaved: false)
    }

    func testANewUsersFirstPlainLaunchShowsTheWelcomeUntilItIsFinished() async {
        let first = await tour.onLaunch(plain: true)
        XCTAssertTrue(first)
        XCTAssertEqual(preferences.welcome, .pending)
        let again = await tour.onLaunch(plain: true)
        XCTAssertTrue(again, "left unfinished, it shows again")

        tour.finishWelcome()
        XCTAssertEqual(preferences.welcome, .seen)
        let afterwards = await tour.onLaunch(plain: true)
        XCTAssertFalse(afterwards)
    }

    func testALaunchThatOpensALinkWaitsForTheNextPlainLaunch() async {
        let fromLink = await tour.onLaunch(plain: false)
        XCTAssertFalse(fromLink)
        XCTAssertEqual(preferences.welcome, .pending)

        recipes.history.send([summary(1)])
        let plain = await tour.onLaunch(plain: true)
        XCTAssertTrue(plain)
    }

    func testSomeoneWhoAlreadyHasRecipesNeverGetsTheWelcomeNorTheRecipeAndCookTips() async {
        recipes.history.send([summary(1), summary(2)])

        let shows = await tour.onLaunch(plain: true)
        XCTAssertFalse(shows)
        XCTAssertEqual(preferences.welcome, .seen)
        XCTAssertEqual(preferences.seenTips, [.recipe, .cookMode])
    }

    /// On iOS a share is saved by the extension without opening the app: a new user's first share
    /// leaves the welcome pending, so the recipe it adds doesn't make them look like an old user.
    func testTheExtensionNotesANewUsersFirstShare() async throws {
        let suite = "FirstRunTourTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        await FirstRunTour.noteShare(defaults: defaults, recipes: recipes)
        XCTAssertEqual(defaults.string(forKey: "tour_welcome"), "PENDING")

        let shared = UserDefaultsAppPreferences(defaults: defaults)
        recipes.history.send([summary(1)])
        let shows = await FirstRunTour(preferences: shared, recipes: recipes).onLaunch(plain: true)
        XCTAssertTrue(shows)
    }

    func testTheExtensionLeavesAnOldUserUndecided() async throws {
        let suite = "FirstRunTourTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        recipes.history.send([summary(1)])

        await FirstRunTour.noteShare(defaults: defaults, recipes: recipes)
        XCTAssertNil(defaults.string(forKey: "tour_welcome"))
    }

    func testTheSampleIsAddedOnceInTheUILanguageAndNotAgainAfterItIsDeleted() async {
        await tour.addSampleOnce(language: "de")
        XCTAssertEqual(recipes.addSampleCalls.map(\.language), ["de"])
        XCTAssertEqual(recipes.addSampleCalls.first?.sourceUrl, SampleRecipe.sourceUrl)
        XCTAssertTrue(preferences.sampleAdded)

        recipes.sample = nil // deleted
        await tour.addSampleOnce(language: "de")
        XCTAssertEqual(recipes.addSampleCalls.count, 1)
    }

    func testAFailedSaveTriesAgainAtTheNextWelcome() async {
        recipes.addSampleResult = nil
        await tour.addSampleOnce(language: "en")
        XCTAssertFalse(preferences.sampleAdded)

        recipes.addSampleResult = 7
        await tour.addSampleOnce(language: "en")
        XCTAssertTrue(preferences.sampleAdded)
        XCTAssertEqual(recipes.sample, 7)
    }

    func testTryItOpensTheSampleThatIsThereAndAddsItAgainOnlyIfGone() async {
        recipes.sample = 5
        let there = await tour.sampleToOpen(language: "en")
        XCTAssertEqual(there, 5)
        XCTAssertTrue(recipes.addSampleCalls.isEmpty)

        recipes.sample = nil
        recipes.addSampleResult = 6
        let added = await tour.sampleToOpen(language: "fr")
        XCTAssertEqual(added, 6)
        XCTAssertEqual(recipes.addSampleCalls.map(\.language), ["fr"])
    }

    func testShowingTheTourAgainBringsEveryTipBack() {
        for tip in Tip.allCases { preferences.setTipSeen(tip, true) }
        tour.replay()
        XCTAssertEqual(preferences.seenTips, [])
    }

    func testTheTourStateIsStoredUnderTheAndroidKeys() throws {
        let suite = "FirstRunTourTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = UserDefaultsAppPreferences(defaults: defaults)
        XCTAssertEqual(prefs.welcome, .undecided)

        prefs.welcome = .pending
        prefs.sampleAdded = true
        prefs.setTipSeen(.cookMode, true)

        XCTAssertEqual(defaults.string(forKey: "tour_welcome"), "PENDING")
        XCTAssertTrue(defaults.bool(forKey: "tour_sample_added"))
        XCTAssertTrue(defaults.bool(forKey: "tour_tip_cook_mode"))
        XCTAssertEqual(UserDefaultsAppPreferences(defaults: defaults).seenTips, [.cookMode])
        defaults.set("LATER", forKey: "tour_welcome")
        XCTAssertEqual(prefs.welcome, .undecided, "an unknown state reads as undecided")
    }
}
