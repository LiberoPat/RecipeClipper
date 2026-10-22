import SwiftUI

/// Settings' index (#286): one row per section, in order, each with its icon, its current value
/// (muted, on the right) and a chevron; a row opens its section's own page
/// (`SettingsSectionScreen`). The values come from the ViewModel, which follows the preferences,
/// so one is never stale. The version sits quietly at the foot; seven taps open Developer
/// settings (#87).
struct SettingsScreen: View {
    let vm: SettingsViewModel
    var onOpenSection: (SettingsSection) -> Void = { _ in }
    /// Seven taps on the version (#87).
    var onOpenDeveloperSettings: () -> Void = {}

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(Strings.settingsTitle, style: Typography.headlineSmall)
                    .padding(.bottom, 12)
                ForEach(vm.sections, id: \.self) { section in
                    SettingsIndexRow(
                        section: section,
                        value: value(section, state),
                        alert: section == .yourRecipes && state.autoBackup?.needsAttention == true
                    ) { onOpenSection(section) }
                }

                // The version, quietly at the foot. Seven taps open Developer settings (#87).
                Text(Strings.settingsVersion(state.appVersion))
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.muted)
                    .padding(.top, 24)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                    .onTapGesture { if vm.onVersionTapped() { onOpenDeveloperSettings() } }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityIdentifier("settings.version")
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
    }

    /// A section's current value as the index shows it, from the settings themselves; Help has none.
    private func value(_ section: SettingsSection, _ state: SettingsUiState) -> String? {
        switch section {
        case .units: return Strings.unitLabel(state.unitSystem)
        case .ovenTemperature: return Strings.temperatureLabel(state.temperatureUnit)
        // Dark mode is the system's call: the one choice here is cook mode's.
        case .appearance: return state.darkWhileCooking ? Strings.darkWhileCookingTitle : Strings.settingsValueSystemTheme
        case .steps: return state.amountsInSteps ? Strings.settingsValueAmountsOn : Strings.settingsValueAmountsOff
        case .pantry: return state.expiryReminders ? Strings.settingsValueRemindersOn : Strings.settingsValueRemindersOff
        // The automatic copy's "Last backed up", as its page says it, but how long ago.
        case .yourRecipes:
            guard let row = state.autoBackup else { return nil }
            guard let at = row.lastBackupAt else { return Strings.autoBackupNever }
            let date = min(Date(timeIntervalSince1970: TimeInterval(at) / 1000), Date())
            let formatter = RelativeDateTimeFormatter()
            formatter.dateTimeStyle = .named
            return Strings.autoBackupLast(formatter.localizedString(for: date, relativeTo: Date()))
        case .help: return nil
        case .unlimited: return vm.unlockRow?.unlocked == true ? Strings.settingsValueUnlocked : nil
        }
    }
}

/// One section on the index: its icon, its name, its value and a chevron, the whole row one
/// button, with a hairline under it. The value wraps beside the name, never over it; at the
/// accessibility sizes it moves under the name instead.
private struct SettingsIndexRow: View {
    let section: SettingsSection
    let value: String?
    let alert: Bool
    let action: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(action: action) {
            VStack(spacing: 0) {
                HStack(spacing: 14) {
                    Image(systemName: section.symbol)
                        .font(.system(size: 19))
                        .foregroundStyle(Palette.accentText)
                        .frame(width: 24)
                        .accessibilityHidden(true)
                    if dynamicTypeSize.isAccessibilitySize {
                        VStack(alignment: .leading, spacing: 2) {
                            name
                            if let value { valueText(value) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        // No priority either way: each text gets up to half and wraps past it.
                        name
                        Spacer(minLength: 12)
                        if let value { valueText(value).multilineTextAlignment(.trailing) }
                    }
                    Text("›").textStyle(Typography.titleLarge).foregroundStyle(Palette.muted)
                        .accessibilityHidden(true)
                }
                .padding(.vertical, 14)
                .contentShape(Rectangle())
                Hairline()
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(section.identifier)
    }

    private var name: some View {
        Text(section.title).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
    }

    private func valueText(_ value: String) -> some View {
        Text(value).textStyle(Typography.bodyMedium).foregroundStyle(alert ? Palette.error : Palette.muted)
    }
}
