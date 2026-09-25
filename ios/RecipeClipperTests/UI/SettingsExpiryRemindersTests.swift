import XCTest
@testable import RecipeClipper

/// The Pantry section's "Expiry reminders" switch (#52; Android's SettingsExpiryRemindersTest).
@MainActor
final class SettingsExpiryRemindersTests: XCTestCase {
    private let preferences = FakeAppPreferences()
    private let flags = FeatureFlags(store: MemoryFeatureFlagStore(), definitions: [
        FlagDefinition(key: "mealPlan", description: "The meal plan", defaults: .init(debug: false, release: false), issue: 47)
    ], isDebug: true)

    private func vm(granted: Bool = true) -> SettingsViewModel {
        SettingsViewModel(
            preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles(),
            flags: flags, notificationPermission: FixedNotificationPermission(granted: granted)
        )
    }

    func testThePantrySectionFollowsTheMealPlanFlag() {
        let vm = vm()
        XCTAssertFalse(vm.showsPantry)
        flags.set(.mealPlan, true)
        XCTAssertTrue(vm.showsPantry)
    }

    func testAllowedNotificationsTurnRemindersOn() async {
        let vm = vm(granted: true)
        await vm.onExpiryRemindersChange(true)?.value
        XCTAssertTrue(preferences.expiryReminders)
        XCTAssertTrue(vm.uiState.expiryReminders)
        XCTAssertFalse(vm.uiState.expiryRemindersDenied)
    }

    func testRefusedNotificationsKeepRemindersOffAndSayWhy() async {
        let vm = vm(granted: false)
        await vm.onExpiryRemindersChange(true)?.value
        XCTAssertFalse(preferences.expiryReminders)
        XCTAssertFalse(vm.uiState.expiryReminders)
        XCTAssertTrue(vm.uiState.expiryRemindersDenied)

        // A later change to another setting keeps the explanation.
        vm.onDarkWhileCookingChange(true)
        try? await Task.sleep(for: .milliseconds(50))
        XCTAssertTrue(vm.uiState.expiryRemindersDenied)
    }

    func testTurningRemindersOff() {
        preferences.expiryReminders = true
        let vm = vm()
        XCTAssertTrue(vm.uiState.expiryReminders)
        vm.onExpiryRemindersChange(false)
        XCTAssertFalse(preferences.expiryReminders)
        XCTAssertFalse(vm.uiState.expiryReminders)
    }
}
