import XCTest
@testable import RecipeClipper

/// Pro (#413) through `DefaultProAccess` over a fake store, and Settings' Pro row (Android's
/// `ProAccessTest`, `ProScreenTest` and `SettingsProTest`). Pro adds nothing today (owner, 2026-11-23:
/// the Week extras are free, Pro is kept for future AI features), so there is no Pro page; the first
/// extra brings it back. The free extras are `FreeExtrasTests`'.
@MainActor
final class ProTests: XCTestCase {
    private let flagStore = MemoryFeatureFlagStore()
    private lazy var flags = FeatureFlags(store: flagStore, isDebug: false)
    private let store = FakeEntitlements()
    private lazy var pro = DefaultProAccess(flags: flags, entitlements: store)
    private let today = FakePlanCalendar.wednesday

    // MARK: - Reading Pro

    func testOnByDefaultAndGatedWithNothingBought() {
        XCTAssertTrue(FeatureFlags(store: MemoryFeatureFlagStore(), isDebug: true).isOn(.proUnlock))
        XCTAssertTrue(flags.isOn(.proUnlock))
        XCTAssertEqual(pro.state, ProState(gated: true, isPro: false))
        XCTAssertTrue(pro.state.locked)
    }

    func testACookWhoUnlockedUnlimitedRecipesUnder107IsPro() {
        // The cached entitlement of #107, as StoreKitEntitlements reads it at launch.
        let old = DefaultProAccess(flags: flags, entitlements: FakeEntitlements(UnlockState(unlocked: true)))
        XCTAssertTrue(old.state.isPro)
        XCTAssertTrue(old.state.extras)
    }

    func testDeveloperSettingsOverrideCountsAsProAndResetTakesItAway() {
        flags.setUnlockedOverride(true)
        XCTAssertTrue(pro.state.isPro)
        flags.reset()
        XCTAssertFalse(pro.state.isPro)
    }

    func testWithTheFlagOffNothingIsGated() {
        flags.set(.proUnlock, false)
        XCTAssertFalse(pro.state.gated)
        XCTAssertTrue(pro.state.extras)
    }

    func testProAddsNothingTodaySoThereIsNoProPage() {
        XCTAssertTrue(ProExtras.all.isEmpty)
        XCTAssertTrue(pro.state.gated)
        XCTAssertTrue(pro.state.available.isEmpty)
        XCTAssertFalse(pro.state.showsPage)
    }

    func testTheProPageExistsOnceTheFlagIsOnAndThereIsAnExtra() {
        let extra = ProExtra(title: "T", body: "B")
        XCTAssertTrue(ProState(gated: true, available: [extra]).showsPage)
        XCTAssertFalse(ProState(gated: false, available: [extra]).showsPage)
        XCTAssertFalse(ProState(gated: true, available: []).showsPage)
    }

    // MARK: - The Pro screen

    func testThePriceIsTheStoresAndThereIsNoneUntilItAnswers() {
        let vm = ProViewModel(pro: pro)
        XCTAssertNil(vm.pro.price)
        store.state.price = "3,49 €"
        XCTAssertEqual(vm.pro.price, "3,49 €")
    }

    func testUnlockingBuysOnceAndMakesThePro() async {
        let vm = ProViewModel(pro: pro)
        await vm.onUnlock()?.value
        XCTAssertEqual(store.purchases, 1)
        XCTAssertTrue(vm.pro.isPro)
        XCTAssertNil(vm.uiState.notice)
        XCTAssertFalse(vm.uiState.busy)
    }

    func testARestoreWithNothingToRestoreSaysSoAndAPendingOneWaits() async {
        let vm = ProViewModel(pro: pro)
        await vm.onRestore()?.value
        XCTAssertEqual(store.restores, 1)
        XCTAssertEqual(vm.uiState.notice, .nothingToRestore)
        store.purchaseOutcome = .pending
        await vm.onUnlock()?.value
        XCTAssertEqual(vm.uiState.notice, .pending)
        XCTAssertTrue(vm.pro.pending)
    }

    // MARK: - Settings

    private func settings(
        _ preferences: FakeAppPreferences = FakeAppPreferences(), pro: ProAccess? = nil
    ) -> SettingsViewModel {
        SettingsViewModel(
            preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles(), flags: flags,
            notificationPermission: FixedNotificationPermission(granted: true), entitlements: store, pro: pro ?? self.pro
        )
    }

    private func proWithAnExtra() -> StubProAccess {
        StubProAccess(ProState(gated: true, available: [ProExtra(title: "A future AI extra", body: "What it does.")]))
    }

    func testWithNoProExtrasThereIsNoProRowEvenWithTheFlagOn() {
        flags.set(.freeTier, true)
        let vm = settings()
        XCTAssertTrue(flags.isOn(.proUnlock))
        XCTAssertFalse(vm.showsPro)
        XCTAssertFalse(vm.sections.contains(.pro))
    }

    func testTheProRowComesBackWhenProAddsSomething() {
        flags.set(.freeTier, true)
        let vm = settings(pro: proWithAnExtra())
        XCTAssertTrue(vm.showsPro)
        XCTAssertTrue(vm.sections.contains(.pro))
        XCTAssertFalse(vm.sections.contains(.unlimited))
        XCTAssertNil(vm.unlockRow)
        XCTAssertFalse(vm.isPro)
    }

    func testTheProRowSaysUnlockedForAnOld107Unlock() {
        let access = proWithAnExtra()
        let vm = settings(pro: access)
        access.state.isPro = true
        XCTAssertTrue(vm.isPro)
    }

    func testWithTheFlagOffThereIsNoProRowEvenWithAnExtra() {
        let access = StubProAccess(ProState(gated: false, available: [ProExtra(title: "T", body: "B")]))
        XCTAssertFalse(settings(pro: access).sections.contains(.pro))
    }

    func testTheRemindersAreFreeTheDefaultEveningOneIsOnAndTheSwitchesWork() async {
        let preferences = FakeAppPreferences()
        let vm = settings(preferences)
        XCTAssertTrue(vm.uiState.eveningReminder)
        await vm.onWeekReminderChange(.tonight, true)?.value
        XCTAssertTrue(preferences.tonightReminder)
        vm.onWeekReminderChange(.tonight, false)
        XCTAssertFalse(preferences.tonightReminder)
        await vm.onWeekReminderChange(.evening, true)?.value
        XCTAssertTrue(preferences.eveningReminder)
    }
}

/// A `ProAccess` a test sets the state of: today Pro adds nothing, so a test that needs the Pro page to
/// exist names its own extra (Android's `FakeProAccess`).
@MainActor
final class StubProAccess: ProAccess {
    var state: ProState
    init(_ state: ProState) { self.state = state }
    func purchase() async -> PurchaseOutcome { .failed }
    func restore() async -> PurchaseOutcome { .failed }
}
