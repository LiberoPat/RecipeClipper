import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The app's one settings surface. Exclusive choices are radio rows, independent toggles are
/// switches — never a bare checkmark for either, which is the reason this screen exists.
struct SettingsScreen: View {
    let vm: SettingsViewModel
    /// Seven taps on the version (#87).
    var onOpenDeveloperSettings: () -> Void = {}
    @State private var importing = false

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(Strings.settingsTitle, style: Typography.headlineSmall)
                    .padding(.bottom, 12)

                SectionHeading(Strings.settingsSectionUnits).padding(.bottom, 4)
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

                Divided {
                    SectionHeading(Strings.settingsSectionOvenTemperature).padding(.bottom, 4)
                    ForEach(TemperatureUnit.allCases, id: \.self) { option in
                        RadioRow(
                            title: Strings.temperatureLabel(option),
                            description: Strings.temperatureDescription(option),
                            selected: option == state.temperatureUnit
                        ) { vm.onTemperatureUnitChange(option) }
                    }
                }

                Divided {
                    SectionHeading(Strings.settingsSectionAppearance).padding(.bottom, 4)
                    SwitchRow(
                        title: Strings.darkWhileCookingTitle,
                        description: Strings.darkWhileCookingDescription,
                        isOn: Binding(get: { vm.uiState.darkWhileCooking }, set: vm.onDarkWhileCookingChange)
                    )
                }

                if vm.showsSteps || vm.showsChefMode {
                    Divided {
                        SectionHeading(Strings.settingsSectionSteps).padding(.bottom, 4)
                        if vm.showsSteps {
                            SwitchRow(
                                title: Strings.amountsInStepsTitle,
                                description: Strings.amountsInStepsDescription,
                                isOn: Binding(get: { vm.uiState.amountsInSteps }, set: vm.onAmountsInStepsChange)
                            )
                        }
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
                    .onAppear { vm.onStepsShown() }
                }

                if vm.showsPantry {
                    Divided {
                        SectionHeading(Strings.settingsSectionPantry).padding(.bottom, 4)
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
                }

                Divided {
                    SectionHeading(Strings.settingsSectionYourRecipes).padding(.bottom, 4)
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

                // "Unlimited recipes" (#107), only with the `freeTier` flag. Actions, not choices,
                // so plain rows; the unlocked state is a sentence, never a bare checkmark.
                if let row = vm.unlockRow {
                    Divided {
                        SectionHeading(Strings.unlimitedTitle).padding(.bottom, 4)
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
                            if let status = row.pending ? Strings.unlimitedPending : state.unlockNotice.map(Strings.unlockNotice) {
                                Text(status)
                                    .textStyle(Typography.bodyMedium)
                                    .foregroundStyle(Palette.onBackground)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .accessibilityIdentifier("settings.unlockStatus")
                            }
                        }
                    }
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
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .zip]) { result in
            switch result {
            case .success(let url): vm.onImportPicked(url)
            case .failure: vm.onImportPickFailed()
            }
        }
    }

    /// Chef mode's one line: the recipe languages it writes, or why the switch is off.
    private func chefNote(_ support: ChefSupport?) -> String? {
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

    private func shareURL(_ status: BackupStatus) -> URL? {
        if case .readyToShare(let url) = status { return url }
        return nil
    }

}

/// Progress, or the outcome of the last export or import, until the next one; Home's Restore
/// (#150) shows its outcome with it too.
func backupStatusText(_ status: BackupStatus) -> (text: String, isError: Bool)? {
    switch status {
    case .idle, .readyToShare: return nil
    case .exporting: return (Strings.backupExporting, false)
    case .importing: return (Strings.backupImporting, false)
    case .imported(let summary): return (Strings.importSummary(summary), false)
    case .failed(let error): return (Strings.message(for: error), true)
    }
}

/// The automatic backup copy (#150): the switch, when the last copy was written (and whether
/// something is wrong), and "Back up now". Quiet lines, in the error colour only when the copy
/// has stopped working.
private struct AutoBackupRows: View {
    let row: AutoBackupRow
    let onEnabledChange: (Bool) -> Void
    let onBackUpNow: () -> Void

    var body: some View {
        SwitchRow(
            title: Strings.autoBackupTitle,
            description: Strings.autoBackupDescription,
            isOn: Binding(get: { row.enabled }, set: onEnabledChange)
        )
        .accessibilityIdentifier("settings.autoBackup")
        ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
            Text(line.text)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(line.alert ? Palette.error : Palette.muted)
                .padding(.top, 2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        ActionRow(
            title: row.running ? Strings.autoBackupRunning : Strings.autoBackupNow,
            description: nil,
            enabled: row.canBackUpNow,
            action: onBackUpNow
        )
        .accessibilityIdentifier("settings.backUpNow")
    }

    private var lines: [(text: String, alert: Bool)] {
        var lines: [(text: String, alert: Bool)] = []
        if row.destination == .unavailable { lines.append((Strings.autoBackupICloudUnavailable, true)) }
        let last = row.lastBackupAt.map {
            Strings.autoBackupLast(Date(timeIntervalSince1970: TimeInterval($0) / 1000)
                .formatted(date: .abbreviated, time: .shortened))
        } ?? Strings.autoBackupNever
        lines.append((last, false))
        if row.lastFailed && row.destination == .ready { lines.append((Strings.autoBackupFailed, true)) }
        if row.nudge { lines.append((Strings.autoBackupNudge, true)) }
        return lines
    }
}

/// A row that does something when tapped: a title and, usually, a one-line description.
private struct ActionRow: View {
    let title: String
    let description: String?
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            TitleAndDescription(title: title, description: description)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

/// Presents the system share sheet on `item` when it appears, from this view: on iPad the
/// sheet is a popover and needs a source view to point at, which a SwiftUI `.sheet` wrapping a
/// UIActivityViewController wouldn't give it. `onDone` runs when the sheet goes away (or
/// couldn't be shown).
struct ShareSheetAnchor: UIViewRepresentable {
    let item: URL?
    let onDone: () -> Void

    final class Coordinator {
        var presented: URL?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        guard let item else {
            context.coordinator.presented = nil
            return
        }
        guard context.coordinator.presented != item else { return }
        context.coordinator.presented = item
        let onDone = onDone
        DispatchQueue.main.async {
            guard var presenter = view.window?.rootViewController else { return onDone() }
            while let next = presenter.presentedViewController { presenter = next }
            let controller = UIActivityViewController(activityItems: [item], applicationActivities: nil)
            controller.popoverPresentationController?.sourceView = view
            controller.popoverPresentationController?.sourceRect = view.bounds
            controller.completionWithItemsHandler = { _, _, _, _ in onDone() }
            presenter.present(controller, animated: true)
        }
    }
}

/// A section preceded by a hairline with breathing room either side.
private struct Divided<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        Hairline().padding(.vertical, 16)
        content()
    }
}

/// An exclusive-choice row: a radio circle, a title and a one-line description. The whole row
/// is the tap target.
private struct RadioRow: View {
    let title: String
    let description: String
    let selected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 12) {
                RadioGlyph(selected: selected)
                TitleAndDescription(title: title, description: description)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// An independent toggle: a title, a description and a switch.
struct SwitchRow: View {
    let title: String
    let description: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            TitleAndDescription(title: title, description: description)
        }
        .toggleStyle(StackedWhenLargeToggleStyle())
        .tint(Palette.primary)
        .padding(.vertical, 10)
    }
}

/// The system switch beside its label normally. At the accessibility sizes the label gets the
/// full width and the switch sits under it: beside a 51pt switch the description was wrapping
/// two or three words to a line.
private struct StackedWhenLargeToggleStyle: ToggleStyle {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func makeBody(configuration: Configuration) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                // Hidden from VoiceOver: the switch below still carries it as its label.
                configuration.label.accessibilityHidden(true)
                Toggle(configuration).labelsHidden()
            }
        } else {
            Toggle(configuration)
        }
    }
}

private struct TitleAndDescription: View {
    let title: String
    let description: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
            if let description {
                Text(description).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
