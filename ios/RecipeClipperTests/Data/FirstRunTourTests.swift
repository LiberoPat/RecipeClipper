import XCTest
@testable import RecipeClipper

/// Port of Android's FirstRunTourTest: the first-run tour's sample recipe (#151, #190) over fakes.
@MainActor
final class FirstRunTourTests: XCTestCase {
    private var preferences: MemoryTourPreferences!
    private var recipes: FakeRecipeRepository!
    private var tour: FirstRunTour!

    override func setUp() {
        preferences = MemoryTourPreferences(sampleAdded: false, seen: [])
        recipes = FakeRecipeRepository()
        tour = FirstRunTour(preferences: preferences, recipes: recipes)
    }

    private func summary(_ id: Int64) -> RecipeSummary {
        RecipeSummary(id: id, title: "Recipe \(id)", imageUrl: nil, totalTime: nil, lastViewedAt: id, isSaved: false)
    }

    func testANewUsersFirstLaunchAddsTheSampleQuietlyInTheUILanguage() async {
        await tour.onLaunch(language: "de")
        XCTAssertEqual(recipes.addSampleCalls.map(\.language), ["de"])
        XCTAssertEqual(recipes.addSampleCalls.first?.sourceUrl, SampleRecipe.sourceUrl)
        XCTAssertTrue(preferences.sampleAdded)
    }

    func testDeletedTheSampleStaysDeleted() async {
        await tour.onLaunch(language: "en")
        recipes.sample = nil // deleted
        await tour.onLaunch(language: "en")
        XCTAssertEqual(recipes.addSampleCalls.count, 1)
    }

    func testSomeoneWhoAlreadyHasRecipesNeverGetsTheSample() async {
        recipes.history.send([summary(1)])
        await tour.onLaunch(language: "en")
        XCTAssertTrue(recipes.addSampleCalls.isEmpty)
        XCTAssertTrue(preferences.sampleAdded, "decided once")

        recipes.history.send([]) // they deleted everything
        await tour.onLaunch(language: "en")
        XCTAssertTrue(recipes.addSampleCalls.isEmpty)
    }

    func testASampleAlreadyThereIsntAddedTwice() async {
        recipes.sample = 5
        await tour.onLaunch(language: "en")
        XCTAssertTrue(recipes.addSampleCalls.isEmpty)
        XCTAssertTrue(preferences.sampleAdded)
    }

    func testAFailedSaveTriesAgainAtTheNextLaunch() async {
        recipes.addSampleResult = nil
        await tour.onLaunch(language: "en")
        XCTAssertFalse(preferences.sampleAdded)

        recipes.addSampleResult = 7
        await tour.onLaunch(language: "en")
        XCTAssertTrue(preferences.sampleAdded)
        XCTAssertEqual(recipes.sample, 7)
    }

    func testEveryLaunchFormatsTheTimesOfASampleSavedBefore179() async {
        await tour.onLaunch(language: "en")
        await tour.onLaunch(language: "en")
        XCTAssertEqual(recipes.formatSampleTimesCalls, 2)
    }

    /// On iOS a share is saved by the extension without opening the app: a new user's first share
    /// adds the sample first, as the app's first launch would have, and the app then adds nothing.
    func testTheExtensionAddsTheSampleBeforeANewUsersFirstShare() async throws {
        let suite = "FirstRunTourTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        await FirstRunTour.beforeShare(defaults: defaults, recipes: recipes, language: "fr")
        XCTAssertEqual(recipes.addSampleCalls.map(\.language), ["fr"])
        XCTAssertTrue(defaults.bool(forKey: "tour_sample_added"))

        recipes.history.send([summary(1)]) // the shared recipe
        await FirstRunTour(preferences: UserDefaultsAppPreferences(defaults: defaults), recipes: recipes)
            .onLaunch(language: "fr")
        XCTAssertEqual(recipes.addSampleCalls.count, 1)
    }

    func testTheExtensionAddsNoSampleForSomeoneWithRecipes() async throws {
        let suite = "FirstRunTourTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        recipes.history.send([summary(1)])

        await FirstRunTour.beforeShare(defaults: defaults, recipes: recipes, language: "en")
        XCTAssertTrue(recipes.addSampleCalls.isEmpty)
        XCTAssertTrue(defaults.bool(forKey: "tour_sample_added"))
    }

    func testTheTourStateIsStoredUnderTheAndroidKeys() throws {
        let suite = "FirstRunTourTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = UserDefaultsAppPreferences(defaults: defaults)
        XCTAssertFalse(prefs.sampleAdded)
        XCTAssertEqual(prefs.seenTooltips, [])

        prefs.sampleAdded = true
        prefs.setTooltipSeen(.cookTimer, true)

        XCTAssertTrue(defaults.bool(forKey: "tour_sample_added"))
        XCTAssertTrue(defaults.bool(forKey: "tooltip_cook_timer"))
        XCTAssertEqual(UserDefaultsAppPreferences(defaults: defaults).seenTooltips, [.cookTimer])

        prefs.setTooltipSeen(.cookTimer, false)
        XCTAssertNil(defaults.object(forKey: "tooltip_cook_timer"), "unseen again removes the key")
    }

    /// #151's keys are ignored: someone who dismissed every tip still gets the tooltips (the
    /// owner's decision, reversing #163).
    func testEveryoneStartsWithEveryTooltipUnseenWhatever151Stored() throws {
        let suite = "FirstRunTourTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("SEEN", forKey: "tour_welcome")
        defaults.set(true, forKey: "tour_tip_recipe")
        defaults.set(true, forKey: "tour_tip_cook_mode")
        XCTAssertEqual(UserDefaultsAppPreferences(defaults: defaults).seenTooltips, [])
    }
}
