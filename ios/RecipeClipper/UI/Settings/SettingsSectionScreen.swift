import SwiftUI
import UniformTypeIdentifiers

/// One section of Settings on a page of its own (#286; Android's `SettingsSectionScreen`),
/// opened from its row on the index; Back returns there. It holds exactly that section's
/// controls, as the one long screen had them: exclusive choices are radio rows, toggles are
/// switches, actions are plain rows. Its ViewModel is its own, following the preferences like
/// the index's, so a change here is on the index when the cook goes back.
///
/// Help hosts Settings' tooltip (#190), "Show tips again": the visit is the Help page's.
struct SettingsSectionScreen: View {
    let section: SettingsSection
    let vm: SettingsViewModel
    /// "Show tips again" (#190).
    var onShowTips: () -> Void = {}
    @State private var importing = false

    var body: some View {
        let page = ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(section.title, style: Typography.headlineSmall)
                    .padding(.bottom, 12)
                rows
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .zip]) { result in
            switch result {
            case .success(let url): vm.onImportPicked(url)
            case .failure: vm.onImportPickFailed()
            }
        }
        if section == .help {
            page.tooltipHost(.settings)
        } else {
            page
        }
    }

    @ViewBuilder
    private var rows: some View {
        let state = vm.uiState
        switch section {
        case .units:
            ForEach(UnitSystem.allCases, id: \.self) { option in
                RadioRow(
                    title: Strings.unitLabel(option),
                    description: Strings.unitDescription(option),
                    selected: option == state.unitSystem
                ) { vm.onUnitSystemChange(option) }
            }
            if vm.showsConvertLiquids {
                SwitchRow(
                    title: Strings.convertLiquidsTitle,
                    description: Strings.convertLiquidsDescription,
                    isOn: Binding(get: { vm.uiState.convertLiquids }, set: vm.onConvertLiquidsChange)
                )
            }
        case .ovenTemperature:
            ForEach(TemperatureUnit.allCases, id: \.self) { option in
                RadioRow(
                    title: Strings.temperatureLabel(option),
                    description: Strings.temperatureDescription(option),
                    selected: option == state.temperatureUnit
                ) { vm.onTemperatureUnitChange(option) }
            }
        case .appearance:
            SwitchRow(
                title: Strings.darkWhileCookingTitle,
                description: Strings.darkWhileCookingDescription,
                isOn: Binding(get: { vm.uiState.darkWhileCooking }, set: vm.onDarkWhileCookingChange)
            )
        case .steps: stepsRows
        case .pantry: pantryRows
        case .yourRecipes: yourRecipesRows
        case .help:
            // "Show tips again" (#190): an action, so a plain row.
            ActionRow(
                title: Strings.settingsShowTips,
                description: Strings.settingsShowTipsDescription,
                enabled: true,
                action: onShowTips
            )
            .accessibilityIdentifier("settings.showTips")
            .tooltipAnchor(.settingsShowTips)
        case .unlimited: unlimitedRows
        }
    }
}

private extension SettingsSectionScreen {
    @ViewBuilder
    var stepsRows: some View {
        let state = vm.uiState
        SwitchRow(
            title: Strings.amountsInStepsTitle,
            description: Strings.amountsInStepsDescription,
            isOn: Binding(get: { vm.uiState.amountsInSteps }, set: vm.onAmountsInStepsChange)
        )
        .onAppear { vm.onStepsShown() }
        if vm.showsChefMode {
            // Chef mode (#100): disabled, with one line saying why, where the phone can't.
            let available = state.chefSupport?.isAvailable ?? false
            SwitchRow(
                title: Strings.chefModeTitle,
                description: Strings.chefModeDescription,
                isOn: Binding(get: { vm.uiState.chefMode && available }, set: vm.onChefModeChange)
            )
            .disabled(!available)
            .accessibilityIdentifier("settings.chefMode")
            if let note = chefNote(state.chefSupport) {
                Text(note)
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("settings.chefModeNote")
            }
        }
    }

    @ViewBuilder
    var pantryRows: some View {
        let state = vm.uiState
        SwitchRow(
            title: Strings.expiryRemindersTitle,
            description: Strings.expiryRemindersDescription,
            isOn: Binding(get: { vm.uiState.expiryReminders }, set: { vm.onExpiryRemindersChange($0) })
        )
        .accessibilityIdentifier("settings.expiryReminders")
        if state.expiryRemindersDenied && !state.expiryReminders {
            Text(Strings.expiryRemindersDenied)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.error)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("settings.expiryRemindersDenied")
        }
    }

    /// Your recipes: the automatic backup copy (#150), then Export and Import (#26), then their outcome.
    @ViewBuilder
    var yourRecipesRows: some View {
        let state = vm.uiState
        if let row = state.autoBackup {
            AutoBackupRows(row: row, onEnabledChange: vm.onAutoBackupChange, onBackUpNow: { vm.onBackUpNow() })
        }
        // Actions, not choices: plain rows, no radio or switch.
        ActionRow(
            title: Strings.backupExportTitle,
            description: Strings.backupExportDescription,
            enabled: !state.backup.isBusy
        ) { vm.onExport() }
        // The share sheet anchors here, so on iPad its popover points at this row.
        .background(ShareSheetAnchor(item: shareURL(state.backup), onDone: vm.onExportShared))
        .accessibilityIdentifier("settings.export")
        ActionRow(
            title: Strings.backupImportTitle,
            description: Strings.backupImportDescription,
            enabled: !state.backup.isBusy
        ) { importing = true }
        .accessibilityIdentifier("settings.import")
        if let status = backupStatusText(state.backup) {
            Text(status.text)
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(status.isError ? Palette.error : Palette.onBackground)
                .padding(.top, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("settings.backupStatus")
        }
    }

    /// "Unlimited recipes" (#107), only with the `freeTier` flag. Actions, not choices, so plain
    /// rows; the unlocked state is a sentence, never a bare checkmark.
    @ViewBuilder
    var unlimitedRows: some View {
        if let row = vm.unlockRow {
            if row.unlocked {
                Text(Strings.unlimitedUnlocked)
                    .textStyle(Typography.bodyLarge)
                    .foregroundStyle(Palette.onBackground)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("settings.unlocked")
            } else {
                ActionRow(
                    title: row.price.map(Strings.unlockPrice) ?? Strings.unlock,
                    description: Strings.unlimitedBody,
                    enabled: !row.busy
                ) { vm.onUnlock() }
                .accessibilityIdentifier("settings.unlock")
                ActionRow(title: Strings.unlimitedRestore, description: nil, enabled: !row.busy) { vm.onRestore() }
                    .accessibilityIdentifier("settings.restore")
                if let status = row.pending ? Strings.unlimitedPending : vm.uiState.unlockNotice.map(Strings.unlockNotice) {
                    Text(status)
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.onBackground)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("settings.unlockStatus")
                }
            }
        }
    }

    /// Chef mode's one line: the recipe languages it writes, or why the switch is off.
    func chefNote(_ support: ChefSupport?) -> String? {
        switch support {
        case nil: return nil
        case .available(let languages):
            let names = languages.map { Locale.current.localizedString(forLanguageCode: $0) ?? $0 }.sorted()
            return Strings.chefModeLanguages(names.joined(separator: ", "))
        case .notEnabled: return Strings.chefModeNotEnabled
        case .notReady: return Strings.chefModeNotReady
        case .unsupported: return Strings.chefModeUnsupported
        }
    }

    func shareURL(_ status: BackupStatus) -> URL? {
        if case .readyToShare(let url) = status { return url }
        return nil
    }
}
