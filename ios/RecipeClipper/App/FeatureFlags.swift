import Foundation
import Observation

/// Every feature flag (#87), one case per entry in `shared/flags.json`, which both apps read.
/// `FeatureFlagsTests` fails if the two lists differ, or if a flag is no longer referenced
/// anywhere: retiring a flag deletes it here, in flags.json and its branches in one PR.
/// Android's twin is `Flag` in `data/flags/FeatureFlags.kt`, with the same keys.
enum Flag: String, CaseIterable {
    /// The meal plan (#47, #49–#51): the bottom tabs and the recipe screen's plan actions.
    case mealPlan
    /// Chef mode (#100): the Settings switch for short steps written on the device.
    case chefMode
    /// Ingredient amounts inside steps (#101): the Settings switch and what it shows.
    case amountsInSteps
    /// The free tier (#107): 20 recipes, and the one-time unlock for unlimited ones.
    case freeTier
    /// A recipe picked from the page's text by the on-device model when the page has no recipe data (#103).
    case llmExtraction
    /// Typed decisions by the on-device model: count brackets, close pantry names, aisles (#104).
    case aiDecisions
    /// "I made this" (#116): your photos and notes on a recipe, and the Recently cooked sort.
    case cookedPhotos
}

/// One flag as `shared/flags.json` declares it.
struct FlagDefinition: Equatable, Decodable {
    struct Defaults: Equatable, Decodable {
        let debug: Bool
        let release: Bool
    }

    let key: String
    let description: String
    let defaults: Defaults
    let issue: Int

    func defaultValue(isDebug: Bool) -> Bool { isDebug ? defaults.debug : defaults.release }
}

/// `shared/flags.json`, bundled beside the app like the shared tables.
enum FlagRegistry {
    private struct File: Decodable { let flags: [FlagDefinition] }

    static func parse(_ data: Data) throws -> [FlagDefinition] {
        try JSONDecoder().decode(File.self, from: data).flags
    }

    static let definitions: [FlagDefinition] = {
        guard let url = Bundle.main.url(forResource: "flags", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let flags = try? parse(data) else {
            assertionFailure("flags.json is missing from the bundle or malformed")
            return []
        }
        return flags
    }()

    static var isDebugBuild: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }
}

/// Where the Developer settings overrides live: only the flags that differ from their default,
/// by key. `UserDefaultsFeatureFlagStore` is the real one (its own suite, never the settings');
/// `MemoryFeatureFlagStore` holds them for a run.
protocol FeatureFlagStore: AnyObject {
    var overrides: [String: Bool] { get }
    /// nil removes the override, so the flag follows its default again.
    func setOverride(_ key: String, _ value: Bool?)
    func clear()
}

final class UserDefaultsFeatureFlagStore: FeatureFlagStore {
    /// Its own suite, apart from the settings in the App Group suite, so a reset can never touch
    /// those. The share extension never reads flags, so it stays out of the App Group.
    static let suiteName = "RecipeClipperFeatureFlags"

    private let defaults: UserDefaults
    private let suite: String

    init(suiteName: String = suiteName) {
        suite = suiteName
        defaults = UserDefaults(suiteName: suiteName) ?? .standard
    }

    var overrides: [String: Bool] {
        (defaults.persistentDomain(forName: suite) ?? [:]).compactMapValues { $0 as? Bool }
    }

    func setOverride(_ key: String, _ value: Bool?) {
        if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
    }

    func clear() {
        defaults.removePersistentDomain(forName: suite)
    }
}

final class MemoryFeatureFlagStore: FeatureFlagStore {
    private(set) var overrides: [String: Bool]

    init(_ overrides: [String: Bool] = [:]) { self.overrides = overrides }

    func setOverride(_ key: String, _ value: Bool?) { overrides[key] = value }
    func clear() { overrides = [:] }
}

/// Typed access to the flags: each is its build type's default from `shared/flags.json` unless
/// the store holds an override. Observable, so a view reading `isOn` (the root's tab shell)
/// redraws when Developer settings changes it, with no restart. Platform-free.
@MainActor
@Observable
final class FeatureFlags {
    let definitions: [FlagDefinition]
    @ObservationIgnored private let store: FeatureFlagStore
    @ObservationIgnored private let isDebug: Bool
    private var overrides: [String: Bool]

    init(
        store: FeatureFlagStore,
        definitions: [FlagDefinition] = FlagRegistry.definitions,
        isDebug: Bool = FlagRegistry.isDebugBuild
    ) {
        self.store = store
        self.definitions = definitions
        self.isDebug = isDebug
        overrides = store.overrides
    }

    func definition(_ flag: Flag) -> FlagDefinition? { definitions.first { $0.key == flag.rawValue } }

    func defaultValue(_ flag: Flag) -> Bool { definition(flag)?.defaultValue(isDebug: isDebug) ?? false }

    func isOn(_ flag: Flag) -> Bool { overrides[flag.rawValue] ?? defaultValue(flag) }

    func isOverridden(_ flag: Flag) -> Bool { overrides[flag.rawValue] != nil }

    /// Choosing the default removes the override, so a later change of default still applies.
    func set(_ flag: Flag, _ on: Bool) {
        store.setOverride(flag.rawValue, on == defaultValue(flag) ? nil : on)
        overrides = store.overrides
    }

    func reset() {
        store.clear()
        overrides = store.overrides
    }

    /// The store key of `unlockedOverride`; the same on Android.
    static let unlockedOverrideKey = "override.unlocked"

    /// Developer settings' "Unlocked" (#107): the unlock counts as bought, to test the unlimited
    /// library without a store. Kept in the same store under a key that is no flag's, so Reset
    /// clears it too.
    var unlockedOverride: Bool { overrides[Self.unlockedOverrideKey] == true }

    func setUnlockedOverride(_ on: Bool) {
        store.setOverride(Self.unlockedOverrideKey, on ? true : nil)
        overrides = store.overrides
    }
}
