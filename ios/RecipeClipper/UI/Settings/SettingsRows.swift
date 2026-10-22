import SwiftUI
import UIKit

// Settings' rows (#286: moved from SettingsScreen.swift when each section got a page of its own).

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
struct AutoBackupRows: View {
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
struct ActionRow: View {
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

/// An exclusive-choice row: a radio circle, a title and a one-line description. The whole row
/// is the tap target.
struct RadioRow: View {
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
