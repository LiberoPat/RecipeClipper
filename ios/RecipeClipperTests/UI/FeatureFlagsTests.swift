import XCTest
@testable import RecipeClipper

/// The flag system (#87), and the registry check: shared/flags.json and the code agree, so a
/// dead flag is noticed. Android's twin is `FeatureFlagsTest`.
@MainActor
final class FeatureFlagsTests: XCTestCase {

    private let definitions = [
        FlagDefinition(key: "freeTier", description: "The free tier",
                       defaults: .init(debug: true, release: false), issue: 107)
    ]

    func testEachBuildTypeGetsItsOwnDefault() {
        XCTAssertTrue(FeatureFlags(store: MemoryFeatureFlagStore(), definitions: definitions, isDebug: true).isOn(.freeTier))
        XCTAssertFalse(FeatureFlags(store: MemoryFeatureFlagStore(), definitions: definitions, isDebug: false).isOn(.freeTier))
    }

    func testAnOverrideWinsAndResetReturnsToTheDefault() {
        let store = MemoryFeatureFlagStore()
        let flags = FeatureFlags(store: store, definitions: definitions, isDebug: false)

        flags.set(.freeTier, true)
        XCTAssertTrue(flags.isOn(.freeTier))
        XCTAssertTrue(flags.isOverridden(.freeTier))
        XCTAssertEqual(store.overrides, ["freeTier": true])

        flags.reset()
        XCTAssertFalse(flags.isOn(.freeTier))
        XCTAssertFalse(flags.isOverridden(.freeTier))
    }

    func testChoosingTheDefaultStoresNoOverride() {
        let store = MemoryFeatureFlagStore(["freeTier": true])
        FeatureFlags(store: store, definitions: definitions, isDebug: false).set(.freeTier, false)
        XCTAssertEqual(store.overrides, [:])
    }

    func testAFlagMissingFromTheRegistryIsOff() {
        XCTAssertFalse(FeatureFlags(store: MemoryFeatureFlagStore(), definitions: [], isDebug: true).isOn(.freeTier))
    }

    func testTheUserDefaultsStoreKeepsOnlyItsOwnSuite() {
        let suite = "FeatureFlagsTests"
        let store = UserDefaultsFeatureFlagStore(suiteName: suite)
        store.clear()
        defer { store.clear() }

        store.setOverride("freeTier", true)
        XCTAssertEqual(UserDefaultsFeatureFlagStore(suiteName: suite).overrides, ["freeTier": true])
        store.setOverride("freeTier", nil)
        XCTAssertEqual(store.overrides, [:])
    }

    /// mealPlan, amountsInSteps and cookedPhotos were retired (#242); a phone may still hold an
    /// override for one from Developer settings. It names no `Flag`, so it changes nothing.
    func testAStoredOverrideForARetiredFlagIsIgnored() {
        let suite = "FeatureFlagsTests.retired"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(false, forKey: "mealPlan")
        defaults.set(false, forKey: "amountsInSteps")
        defaults.set(false, forKey: "cookedPhotos")
        defaults.set("not a boolean", forKey: "reddit")

        let store = UserDefaultsFeatureFlagStore(suiteName: suite)
        let flags = FeatureFlags(store: store, definitions: FlagRegistry.definitions, isDebug: false)
        let clean = FeatureFlags(store: MemoryFeatureFlagStore(), definitions: FlagRegistry.definitions, isDebug: false)
        for flag in Flag.allCases {
            XCTAssertEqual(flags.isOn(flag), clean.isOn(flag), flag.rawValue)
            XCTAssertFalse(flags.isOverridden(flag), flag.rawValue)
        }
        XCTAssertEqual(DeveloperSettingsViewModel(flags: flags).uiState.anyChanged, false)

        // Reset clears the strays with everything else.
        flags.reset()
        XCTAssertEqual(store.overrides, [:])
    }

    func testParsesTheRegistryFormat() throws {
        let json = #"{"flags":[{"key":"a","description":"A","defaults":{"debug":true,"release":false},"issue":9}]}"#
        XCTAssertEqual(
            try FlagRegistry.parse(Data(json.utf8)),
            [FlagDefinition(key: "a", description: "A", defaults: .init(debug: true, release: false), issue: 9)]
        )
    }

    // MARK: - The registry check

    func testEveryFlagInFlagsJsonIsAFlagAndViceVersa() {
        XCTAssertEqual(FlagRegistry.definitions.map(\.key).sorted(), Flag.allCases.map(\.rawValue).sorted())
    }

    func testEveryFlagIsReferencedByTheAppsCode() throws {
        // The app's sources, found from this file's path (the tests run on this Mac's simulator).
        let sources = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("RecipeClipper")
        let files = try XCTUnwrap(FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil))
            .compactMap { $0 as? URL }
            .filter { $0.pathExtension == "swift" && $0.lastPathComponent != "FeatureFlags.swift" }
            .compactMap { try? String(contentsOf: $0, encoding: .utf8) }
        XCTAssertFalse(files.isEmpty, "no sources found at \(sources.path)")
        for flag in Flag.allCases {
            XCTAssertTrue(
                files.contains { $0.contains(".\(flag.rawValue))") || $0.contains("Flag.\(flag.rawValue)") },
                "Flag.\(flag.rawValue) is not used anywhere: retire it"
            )
        }
    }
}

@MainActor
final class DeveloperSettingsViewModelTests: XCTestCase {

    private func flags(_ store: FeatureFlagStore = MemoryFeatureFlagStore()) -> FeatureFlags {
        FeatureFlags(store: store, definitions: [
            FlagDefinition(key: "chefMode", description: "Chef mode", defaults: .init(debug: false, release: false), issue: 100)
        ], isDebug: true)
    }

    func testListsEveryFlagWithItsDescriptionIssueAndState() {
        let row = DeveloperSettingsViewModel(flags: flags()).uiState.flags.first
        XCTAssertEqual(row, FlagRow(flag: .chefMode, description: "Chef mode", issue: 100, on: false, changed: false))
    }

    func testASwitchOverridesTheFlagAndResetClearsIt() {
        let flags = flags()
        let vm = DeveloperSettingsViewModel(flags: flags)

        vm.onFlagChange(.chefMode, true)
        XCTAssertTrue(flags.isOn(.chefMode))
        XCTAssertTrue(vm.uiState.anyChanged)

        vm.onReset()
        XCTAssertFalse(flags.isOn(.chefMode))
        XCTAssertFalse(vm.uiState.anyChanged)
    }

    func testTheSeventhTapOnTheVersionOpensDeveloperSettings() {
        let vm = SettingsViewModel(
            preferences: FakeAppPreferences(), backups: FakeBackupRepository(), files: FakeBackupFiles(), appVersion: "2.3 (7)"
        )
        XCTAssertEqual(vm.uiState.appVersion, "2.3 (7)")
        for _ in 1..<SettingsViewModel.developerTaps { XCTAssertFalse(vm.onVersionTapped()) }
        XCTAssertTrue(vm.onVersionTapped())
        for _ in 1..<SettingsViewModel.developerTaps { XCTAssertFalse(vm.onVersionTapped()) }
        XCTAssertTrue(vm.onVersionTapped())
    }
}
