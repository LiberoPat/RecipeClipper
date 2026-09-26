import SwiftUI

/// "Add this list" (#149; Android's ReceiveListSheet): the lines of a list shared in or pasted,
/// each ticked to start, and one button for each place they can go. The Groceries tab shows it
/// in a sheet ("Paste a list"); the share extension in its card. Neither scrolls on its own: the
/// container decides.
struct ReceiveListView: View {
    let vm: ReceiveListViewModel

    var body: some View {
        let state = vm.uiState
        VStack(alignment: .leading, spacing: 0) {
            SectionHeading(Strings.receiveListTitle).padding(.bottom, 8)
            let lines = state.lines ?? []
            if lines.isEmpty {
                Text(Strings.receiveListEmpty)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                    let ticked = !state.unticked.contains(index)
                    Button { vm.onToggle(index) } label: {
                        HStack(spacing: 12) {
                            CheckboxGlyph(checked: ticked)
                            Text(line).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(ticked ? [.isSelected] : [])
                    .accessibilityIdentifier("receiveLine-\(index)")
                }
                let enabled = state.tickedCount > 0 && state.added == nil
                Button(Strings.addToGroceries, action: vm.onAddToGroceries)
                    .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                    .disabled(!enabled)
                    .padding(.top, 12)
                    .accessibilityIdentifier("receiveToGroceries")
                Button(action: vm.onAddToPantry) {
                    Text(Strings.addToPantry).frame(maxWidth: .infinity)
                }
                .buttonStyle(OutlinedActionStyle())
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.4)
                .padding(.top, 8)
                .accessibilityIdentifier("receiveToPantry")
            }
        }
    }
}
