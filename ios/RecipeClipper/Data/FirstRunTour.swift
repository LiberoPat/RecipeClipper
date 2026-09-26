import Combine
import Foundation

/// The first-run tour's bookkeeping (#151), in the same UserDefaults suite as the settings under
/// the same keys as Android's `unit_preferences`: `tour_welcome`, `tour_sample_added` and one
/// `tour_tip_…` per `Tip`. Its own protocol, so the screens that only show settings never see
/// it; `UserDefaultsAppPreferences` implements both.
protocol TourPreferences: AnyObject {
    var welcome: WelcomeState { get set }
    /// The sample recipe has been added once. It is never added again by itself, even when deleted.
    var sampleAdded: Bool { get set }
    /// The tips dismissed so far.
    var seenTips: Set<Tip> { get }
    /// `seenTips`, then every change. Delivery may be asynchronous, so a subscriber receives on main.
    var seenTipsChanges: AnyPublisher<Set<Tip>, Never> { get }
    func setTipSeen(_ tip: Tip, _ seen: Bool)
}

enum TourKeys {
    static let welcome = "tour_welcome"
    static let sampleAdded = "tour_sample_added"
}

/// The first-run tour's rules (#151), platform-free so they are tested over fakes; Android's
/// `FirstRunTour` is the same.
///
/// - The welcome shows once, on a plain launch (not one that opens a link or a notification):
///   capture stays frictionless. On iOS a share is saved by the extension without opening the
///   app, so the extension notes a new user's first share (`noteShare`), and the welcome shows
///   at the first time the app itself is opened.
/// - Someone who already has recipes when the tour first runs (an older version's user, or a
///   restored backup) never gets the welcome, nor the recipe and cook mode tips.
/// - The sample recipe is added once, when the welcome first shows. Deleted, it stays deleted;
///   "Try it" on a later showing of the tour adds it again only if it is gone.
final class FirstRunTour {
    private let preferences: TourPreferences
    private let recipes: RecipeRepository

    init(preferences: TourPreferences, recipes: RecipeRepository) {
        self.preferences = preferences
        self.recipes = recipes
    }

    /// Decides, once per app start, whether the welcome shows now. `plain` is false for a launch
    /// that opens something. The library is counted without the sample, so an interrupted
    /// welcome still shows again.
    func onLaunch(plain: Bool) async -> Bool {
        switch preferences.welcome {
        case .seen: return false
        case .pending: return plain
        case .undecided: break
        }
        if await Self.libraryCount(recipes) > 0 {
            preferences.welcome = .seen
            preferences.setTipSeen(.recipe, true)
            preferences.setTipSeen(.cookMode, true)
            return false
        }
        preferences.welcome = .pending
        return plain
    }

    /// The welcome is showing: the first time ever, the sample recipe is added, in `language`.
    func addSampleOnce(language: String?) async {
        if preferences.sampleAdded { return }
        if await recipes.sampleId() == nil {
            guard await recipes.addSample(SampleRecipe.forLanguage(language)) != nil else { return }
        }
        preferences.sampleAdded = true
    }

    /// "Try it with a sample recipe": the sample's id, added again only if it is gone.
    func sampleToOpen(language: String?) async -> Int64? {
        if let id = await recipes.sampleId() { return id }
        let id = await recipes.addSample(SampleRecipe.forLanguage(language))
        if id != nil { preferences.sampleAdded = true }
        return id
    }

    /// Skip, Start or Try it: the welcome is done.
    func finishWelcome() {
        preferences.welcome = .seen
    }

    /// "Show the tour again" (Settings): every tip shows again, once more.
    func replay() {
        for tip in Tip.allCases { preferences.setTipSeen(tip, false) }
    }

    /// The share extension, before it saves a shared recipe: a user whose library was empty and
    /// who has never opened the app is new, so the welcome waits for the app's first opening
    /// instead of being skipped for the recipe the share adds. It writes the key directly, as
    /// the extension has no `UserDefaultsAppPreferences`.
    static func noteShare(defaults: UserDefaults, recipes: RecipeRepository) async {
        guard WelcomeState(storedName: defaults.string(forKey: TourKeys.welcome)) == .undecided else { return }
        if await libraryCount(recipes) == 0 {
            defaults.set(WelcomeState.pending.rawValue, forKey: TourKeys.welcome)
        }
    }

    /// Every recipe but the sample (`observeCount` leaves it out), as it is now.
    private static func libraryCount(_ recipes: RecipeRepository) async -> Int {
        for await count in recipes.observeCount().values { return count }
        return 0
    }
}

/// Tour state in memory, all of it done: what a container built for unit tests uses, so no
/// welcome or tip appears unless a test asks for one.
final class MemoryTourPreferences: TourPreferences {
    var welcome: WelcomeState
    var sampleAdded: Bool
    private let subject: CurrentValueSubject<Set<Tip>, Never>

    init(welcome: WelcomeState = .seen, sampleAdded: Bool = true, seen: Set<Tip> = Set(Tip.allCases)) {
        self.welcome = welcome
        self.sampleAdded = sampleAdded
        subject = CurrentValueSubject(seen)
    }

    var seenTips: Set<Tip> { subject.value }
    var seenTipsChanges: AnyPublisher<Set<Tip>, Never> { subject.removeDuplicates().eraseToAnyPublisher() }

    func setTipSeen(_ tip: Tip, _ seen: Bool) {
        if seen { subject.value.insert(tip) } else { subject.value.remove(tip) }
    }
}
