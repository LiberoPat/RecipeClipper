import SwiftUI

/// "Update the pantry" (#147; Android's `PantryUseUpSheet`): one row per pantry item the ticked
/// lines used. A worked-out change ("2 lb → 1 lb") has a checkbox, ticked; one that can't be
/// worked out shows its lines as written with keep, running low or out, keep chosen. One button
/// applies it all.
struct PantryUseUpSheet: View {
    let sheet: UseUpSheet
    let vm: PantryUseUpViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeading(Strings.useUpTitle).padding(.bottom, 4)
                Text(Strings.useUpIntro)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 8)
                ForEach(sheet.rows, id: \.item.id) { row in
                    switch row.change {
                    case .subtract(let before, let after):
                        subtractRow(row, before: before, after: after)
                    case .ask:
                        askRow(row)
                    }
                }
                Button(Strings.useUpConfirm, action: vm.onConfirm)
                    .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                    .padding(.top, 12)
                    .accessibilityIdentifier("useUpButton")
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .presentationBackground(Palette.background)
    }

    private func subtractRow(_ row: UseUpRow, before: String, after: String?) -> some View {
        let ticked = sheet.ticked.contains(row.item.id)
        let name = row.item.name
        return Button { vm.onToggle(row.item.id) } label: {
            HStack(alignment: .top, spacing: 12) {
                CheckboxGlyph(checked: ticked)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                    Text(after.map { Strings.useUpChange(before, $0) } ?? Strings.useUpUsedUp(before))
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.accentText)
                    usedLines(row.lines)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // VoiceOver hears "from 2 lb to 1 lb", not an arrow.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(after.map { Strings.cdUseUpChange(name, before, $0) } ?? Strings.cdUseUpUsedUp(name, before))
        .accessibilityAddTraits(ticked ? [.isButton, .isSelected] : [.isButton])
        .accessibilityIdentifier("useUp-\(row.item.id)")
    }

    private func askRow(_ row: UseUpRow) -> some View {
        let name = row.item.name
        let choice = sheet.choice(row.item.id)
        return VStack(alignment: .leading, spacing: 2) {
            Text(name).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
            usedLines(row.lines)
            Text(Strings.useUpCantWorkOut)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.muted)
            // Exclusive choices: radio rows, wrapping on a narrow screen or a large type size.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 16) { choices(row, name, choice) }
                VStack(alignment: .leading, spacing: 4) { choices(row, name, choice) }
            }
            .padding(.top, 4)
        }
        .padding(.vertical, 6)
        .accessibilityIdentifier("useUp-\(row.item.id)")
    }

    @ViewBuilder
    private func choices(_ row: UseUpRow, _ name: String, _ current: UseUpChoice) -> some View {
        ForEach(UseUpChoice.allCases, id: \.self) { option in
            let label = Strings.useUpChoice(option)
            Button { vm.onChoice(row.item.id, option) } label: {
                HStack(spacing: 8) {
                    RadioGlyph(selected: option == current)
                    Text(label).textStyle(Typography.bodyMedium).foregroundStyle(Palette.onBackground)
                }
                .padding(.vertical, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Strings.cdUseUpChoice(name, label))
            .accessibilityAddTraits(option == current ? [.isButton, .isSelected] : [.isButton])
            .accessibilityIdentifier("useUp-\(row.item.id)-\(option)")
        }
    }

    /// The ticked lines that used the item, as the recipe showed them.
    private func usedLines(_ lines: [String]) -> some View {
        Text(lines.joined(separator: " · "))
            .textStyle(Typography.bodySmall)
            .foregroundStyle(Palette.muted)
    }
}
