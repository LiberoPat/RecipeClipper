import XCTest
@testable import RecipeClipper

/// The Week section's reminder switches and times (#397; Android's SettingsWeekRemindersTest).
@MainActor
final class SettingsWeekRemindersTests: XCTestCase {
    private let preferences = FakeAppPreferences()
    private let store = MemoryFeatureFlagStore()
    private lazy var flags = FeatureFlags(store: store, isDebug: true)

    private func vm(granted: Bool = true) -> SettingsViewModel {
        SettingsViewModel(
            preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles(),
            flags: flags, notificationPermission: FixedNotificationPermission(granted: granted)
        )
    }

    func testTheEveningReminderIsOnByDefaultAt1900AndTonightIsOffAt1600() {
        let state = vm().uiState
        XCTAssertTrue(state.eveningReminder)
        XCTAssertEqual(state.eveningReminderMinute, 19 * 60)
        XCTAssertFalse(state.tonightReminder)
        XCTAssertEqual(state.tonightReminderMinute, 16 * 60)
    }

    func testTheWeekSectionIsOnTheIndexOnlyWithTheFlag() {
        let vm = vm()
        XCTAssertTrue(vm.sections.contains(.week))
        flags.set(.eveningReminders, false)
        // Still there for "Show today's meal" (#448), until that flag goes too.
        XCTAssertTrue(vm.sections.contains(.week))
        flags.set(.todayCardClose, false)
        XCTAssertFalse(vm.sections.contains(.week))
    }

    func testAllowedNotificationsTurnTonightOn() async {
        let vm = vm(granted: true)
        await vm.onWeekReminderChange(.tonight, true)?.value
        XCTAssertTrue(preferences.tonightReminder)
        XCTAssertTrue(vm.uiState.tonightReminder)
        XCTAssertFalse(vm.uiState.weekRemindersBlocked)
    }

    func testRefusedNotificationsKeepTheSwitchOffAndSayWhy() async {
        preferences.eveningReminder = false
        let vm = vm(granted: false)
        await vm.onWeekReminderChange(.evening, true)?.value
        XCTAssertFalse(preferences.eveningReminder)
        XCTAssertFalse(vm.uiState.eveningReminder)
        XCTAssertTrue(vm.uiState.weekRemindersDenied)
        XCTAssertTrue(vm.uiState.weekRemindersBlocked)

        // A later change to another setting keeps the explanation.
        vm.onDarkWhileCookingChange(true)
        try? await Task.sleep(for: .milliseconds(50))
        XCTAssertTrue(vm.uiState.weekRemindersBlocked)
    }

    func testAReminderOnWhileThePhoneBlocksNotificationsSaysSoUntilTheyAreAllowed() async {
        let blocked = vm(granted: false)
        await blocked.onWeekPageShown().value
        XCTAssertTrue(blocked.uiState.weekRemindersBlocked)

        let allowed = vm(granted: true)
        await allowed.onWeekPageShown().value
        XCTAssertFalse(allowed.uiState.weekRemindersBlocked)
    }

    func testNothingIsSaidAboutNotificationsWhenNoReminderIsOn() async {
        preferences.eveningReminder = false
        let vm = vm(granted: false)
        await vm.onWeekPageShown().value
        XCTAssertFalse(vm.uiState.weekRemindersBlocked)
    }

    private final class SwitchablePermission: NotificationPermission {
        var granted: Bool
        init(granted: Bool) { self.granted = granted }
        func request() async -> Bool { granted }
        func allowed() async -> Bool { granted }
    }

    func testARefusalIsForgottenOnceTheCookHasAllowedNotifications() async {
        let permission = SwitchablePermission(granted: false)
        let vm = SettingsViewModel(
            preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles(),
            flags: flags, notificationPermission: permission
        )
        await vm.onWeekReminderChange(.evening, true)?.value
        XCTAssertTrue(vm.uiState.weekRemindersDenied)

        // Allowed in the system settings, back on the page.
        permission.granted = true
        await vm.onWeekPageShown().value
        XCTAssertFalse(vm.uiState.weekRemindersDenied)
        XCTAssertFalse(vm.uiState.weekRemindersBlocked)
    }

    func testTurningAReminderOffNeedsNoAsking() {
        let vm = vm()
        vm.onWeekReminderChange(.evening, false)
        XCTAssertFalse(preferences.eveningReminder)
        XCTAssertFalse(vm.uiState.eveningReminder)
    }

    func testEachReminderKeepsItsOwnTime() {
        let vm = vm()
        vm.onWeekReminderTime(.evening, 20 * 60 + 15)
        vm.onWeekReminderTime(.tonight, 15 * 60)
        XCTAssertEqual(preferences.eveningReminderMinute, 20 * 60 + 15)
        XCTAssertEqual(preferences.tonightReminderMinute, 15 * 60)
        XCTAssertEqual(vm.uiState.eveningReminderMinute, 20 * 60 + 15)
        XCTAssertEqual(vm.uiState.tonightReminderMinute, 15 * 60)
    }

    func testATimeOutsideTheDayIsHeldToIt() {
        let vm = vm()
        vm.onWeekReminderTime(.evening, 99_999)
        XCTAssertEqual(preferences.eveningReminderMinute, 24 * 60 - 1)
        vm.onWeekReminderTime(.evening, -5)
        XCTAssertEqual(preferences.eveningReminderMinute, 0)
    }

    func testTheSettingsAreStoredUnderTheSameKeysAsAndroid() {
        let suite = "SettingsWeekRemindersTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let stored = UserDefaultsAppPreferences(defaults: defaults)
        // Unset: the evening reminder is on at 19:00, Tonight is off at 16:00.
        XCTAssertTrue(stored.eveningReminder)
        XCTAssertEqual(stored.eveningReminderMinute, 19 * 60)
        XCTAssertFalse(stored.tonightReminder)
        XCTAssertEqual(stored.tonightReminderMinute, 16 * 60)

        stored.eveningReminder = false
        stored.eveningReminderMinute = 20 * 60
        stored.tonightReminder = true
        stored.tonightReminderMinute = 17 * 60
        XCTAssertEqual(defaults.object(forKey: "evening_reminder") as? Bool, false)
        XCTAssertEqual(defaults.object(forKey: "evening_reminder_minute") as? Int, 20 * 60)
        XCTAssertEqual(defaults.object(forKey: "tonight_reminder") as? Bool, true)
        XCTAssertEqual(defaults.object(forKey: "tonight_reminder_minute") as? Int, 17 * 60)

        // A time outside the day reads as the default.
        defaults.set(99_999, forKey: "evening_reminder_minute")
        XCTAssertEqual(stored.eveningReminderMinute, 19 * 60)
    }
}
