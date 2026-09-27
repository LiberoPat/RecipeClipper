import XCTest
@testable import RecipeClipper

/// The flag system (#87), and the registry check: shared/flags.json and the code agree, so a
/// dead flag is noticed. Android's twin is `FeatureFlagsTest`.
@MainActor
final class FeatureFlagsTests: XCTestCase {

    private let definitions = [
        FlagDefinition(key: "mealPlan", description: "The meal plan",
                       defaults: .init(debug: true, release: false), issue: 47)
    ]

    func testEachBuildTypeGetsItsOwnDefault() {
        XCTAssertTrue(FeatureFlags(store: MemoryFeatureFlagStore(), definitions: definitions, isDebug: true).isOn(.mealPlan))
        XCTAssertFalse(FeatureFlags(store: MemoryFeatureFlagStore(), definitions: definitions, isDebug: false).isOn(.mealPlan))
    }

    func testAnOverrideWinsAndResetReturnsToTheDefault() {
        let store = MemoryFeatureFlagStore()
        let flags = FeatureFlags(store: store, definitions: definitions, isDebug: false)

        flags.set(.mealPlan, true)
        XCTAssertTrue(flags.isOn(.mealPlan))
        XCTAssertTrue(flags.isOverridden(.mealPlan))
        XCTAssertEqual(store.overrides, ["mealPlan": true])

        flags.reset()
        XCTAssertFalse(flags.isOn(.mealPlan))
        XCTAssertFalse(flags.isOverridden(.mealPlan))
    }

    func testChoosingTheDefaultStoresNoOverride() {
        let store = MemoryFeatureFlagStore(["mealPlan": true])
        FeatureFlags(store: store, definitions: definitions, isDebug: false).set(.mealPlan, false)
        XCTAssertEqual(store.overrides, [:])
    }

    func testAFlagMissingFromTheRegistryIsOff() {
        XCTAssertFalse(FeatureFlags(store: MemoryFeatureFlagStore(), definitions: [], isDebug: true).isOn(.mealPlan))
    }

    func testTheUserDefaultsStoreKeepsOnlyItsOwnSuite() {
        let suite = "FeatureFlagsTests"
        let store = UserDefaultsFeatureFlagStore(suiteName: suite)
        store.clear()
        defer { store.clear() }

        store.setOverride("mealPlan", true)
        XCTAssertEqual(UserDefaultsFeatureFlagStore(suiteName: suite).overrides, ["mealPlan": true])
        store.setOverride("mealPlan", nil)
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
            FlagDefinition(key: "mealPlan", description: "The meal plan", defaults: .init(debug: false, release: false), issue: 47)
        ], isDebug: true)
    }

    func testListsEveryFlagWithItsDescriptionIssueAndState() {
        let row = DeveloperSettingsViewModel(flags: flags()).uiState.flags.first
        XCTAssertEqual(row, FlagRow(flag: .mealPlan, description: "The meal plan", issue: 47, on: false, changed: false))
    }

    func testASwitchOverridesTheFlagAndResetClearsIt() {
        let flags = flags()
        let vm = DeveloperSettingsViewModel(flags: flags)

        vm.onFlagChange(.mealPlan, true)
        XCTAssertTrue(flags.isOn(.mealPlan))
        XCTAssertTrue(vm.uiState.anyChanged)

        vm.onReset()
        XCTAssertFalse(flags.isOn(.mealPlan))
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
