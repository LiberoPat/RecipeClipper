import SwiftUI

/// Developer settings (#87), reached only by tapping the version in Settings 7 times, in release
/// builds too. A switch per flag in `shared/flags.json`, then "Reset to defaults". A change
/// applies at once: the root view reads the flags, so the tab shell appears or goes.
struct DeveloperSettingsScreen: View {
    let vm: DeveloperSettingsViewModel

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(Strings.developerSettingsTitle, style: Typography.headlineSmall)
                Text(Strings.developerSettingsIntro)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.top, 8)
                    .padding(.bottom, 8)

                ForEach(state.flags, id: \.flag) { row in
                    SwitchRow(
                        title: row.flag.rawValue,
                        description: details(row),
                        isOn: Binding(get: { row.on }, set: { vm.onFlagChange(row.flag, $0) })
                    )
                    .accessibilityIdentifier("developer.flag.\(row.flag.rawValue)")
                }

                Rectangle().fill(Palette.hairline).frame(height: 1).padding(.top, 16)
                Button(action: vm.onReset) {
                    Text(Strings.developerReset)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(state.anyChanged ? Palette.accentText : Palette.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!state.anyChanged)
                .accessibilityIdentifier("developer.reset")
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
    }

    private func details(_ row: FlagRow) -> String {
        var parts = [row.description]
        if let issue = row.issue { parts.append(Strings.developerFlagIssue(issue)) }
        if row.changed { parts.append(Strings.developerFlagChanged) }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}
