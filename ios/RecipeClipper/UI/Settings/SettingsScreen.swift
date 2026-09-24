import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The app's one settings surface. Exclusive choices are radio rows, independent toggles are
/// switches — never a bare checkmark for either, which is the reason this screen exists.
struct SettingsScreen: View {
    let vm: SettingsViewModel
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

                Divided {
                    SectionHeading(Strings.settingsSectionYourRecipes).padding(.bottom, 4)
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
                    if let status = statusText(state.backup) {
                        Text(status.text)
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(status.isError ? Palette.error : Palette.onBackground)
                            .padding(.top, 8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("settings.backupStatus")
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText]) { result in
            switch result {
            case .success(let url): vm.onImportPicked(url)
            case .failure: vm.onImportPickFailed()
            }
        }
    }

    private func shareURL(_ status: BackupStatus) -> URL? {
        if case .readyToShare(let url) = status { return url }
        return nil
    }

    private func statusText(_ status: BackupStatus) -> (text: String, isError: Bool)? {
        switch status {
        case .idle, .readyToShare: return nil
        case .exporting: return (Strings.backupExporting, false)
        case .importing: return (Strings.backupImporting, false)
        case .imported(let summary): return (Strings.importSummary(summary), false)
        case .failed(let error): return (Strings.message(for: error), true)
        }
    }
}

/// A row that does something when tapped: a title and a one-line description.
private struct ActionRow: View {
    let title: String
    let description: String
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
private struct ShareSheetAnchor: UIViewRepresentable {
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
private struct SwitchRow: View {
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
    let description: String
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
            Text(description).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
