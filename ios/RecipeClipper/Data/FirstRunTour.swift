import Combine
import Foundation

/// The first-run tour's bookkeeping (#151, #190), in the same UserDefaults suite as the settings
/// under the same keys as Android's `unit_preferences`: `tour_sample_added` and one `tooltip_…`
/// per `Tooltip`. #151's `tour_welcome` and `tour_tip_…` keys are ignored: never read or written
/// again. Its own protocol, so the screens that only show settings never see it;
/// `UserDefaultsAppPreferences` implements both.
protocol TourPreferences: AnyObject {
    /// The sample recipe has been added once, or wasn't needed. Never added again by itself, even
    /// when deleted.
    var sampleAdded: Bool { get set }
    /// The tooltips seen so far.
    var seenTooltips: Set<Tooltip> { get }
    /// `seenTooltips`, then every change. Delivery may be asynchronous, so a subscriber receives on main.
    var seenTooltipsChanges: AnyPublisher<Set<Tooltip>, Never> { get }
    func setTooltipSeen(_ tooltip: Tooltip, _ seen: Bool)
}

enum TourKeys {
    static let sampleAdded = "tour_sample_added"
}

/// The first-run tour's sample recipe (#151, #190), platform-free so it is tested over fakes;
/// Android's `FirstRunTour` is the same. There is no welcome any more: the tour is the tooltips
/// (`TooltipsViewModel`), and the sample is added quietly.
///
/// - The sample is added once, at a new user's first launch (an empty library). On iOS a share
///   is saved by the extension without opening the app, so the extension takes the same step
///   first (`beforeShare`): the sample sits under the shared recipe, as on Android.
/// - Someone who already has recipes then (an older version's user, or a restored backup) never
///   gets it. Either way it's decided once: deleted, it stays deleted.
final class FirstRunTour {
    private let preferences: TourPreferences
    private let recipes: RecipeRepository

    init(preferences: TourPreferences, recipes: RecipeRepository) {
        self.preferences = preferences
        self.recipes = recipes
    }

    /// Every launch. The sample goes in in `language`.
    func onLaunch(language: String?) async {
        await recipes.formatSampleTimes() // #179: a sample saved with raw ISO times; a no-op after
        if preferences.sampleAdded { return }
        if await Self.addSampleIfNew(recipes, language: language) { preferences.sampleAdded = true }
    }

    /// The share extension, before it saves a shared recipe: a new user's first share adds the
    /// sample first. It writes the key directly, as the extension has no `UserDefaultsAppPreferences`.
    static func beforeShare(defaults: UserDefaults, recipes: RecipeRepository, language: String?) async {
        guard !defaults.bool(forKey: TourKeys.sampleAdded) else { return }
        if await addSampleIfNew(recipes, language: language) { defaults.set(true, forKey: TourKeys.sampleAdded) }
    }

    /// Adds the sample to an empty library. True once decided: added, there already, or not needed.
    /// The library is counted without the sample, so one added before an interruption stands.
    private static func addSampleIfNew(_ recipes: RecipeRepository, language: String?) async -> Bool {
        guard await libraryCount(recipes) == 0, await recipes.sampleId() == nil else { return true }
        return await recipes.addSample(SampleRecipe.forLanguage(language)) != nil
    }

    /// Every recipe but the sample (`observeCount` leaves it out), as it is now.
    private static func libraryCount(_ recipes: RecipeRepository) async -> Int {
        for await count in recipes.observeCount().values { return count }
        return 0
    }
}

/// Tour state in memory, every tooltip seen: what a container built for unit tests uses, so no
/// tooltip appears unless a test asks for one.
final class MemoryTourPreferences: TourPreferences {
    var sampleAdded: Bool
    private let subject: CurrentValueSubject<Set<Tooltip>, Never>

    init(sampleAdded: Bool = true, seen: Set<Tooltip> = Set(Tooltip.allCases)) {
        self.sampleAdded = sampleAdded
        subject = CurrentValueSubject(seen)
    }

    var seenTooltips: Set<Tooltip> { subject.value }
    var seenTooltipsChanges: AnyPublisher<Set<Tooltip>, Never> { subject.removeDuplicates().eraseToAnyPublisher() }

    func setTooltipSeen(_ tooltip: Tooltip, _ seen: Bool) {
        if seen { subject.value.insert(tooltip) } else { subject.value.remove(tooltip) }
    }
}
