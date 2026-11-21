import SwiftUI

/// The "Select all" / "Clear all" row at the top of a pantry checklist (#406; Android's
/// `SelectAllRow`): a checkbox-style row, filled with a check when every row is ticked and with a
/// dash when some are, whose label says what a tap does. It only changes the sheet's ticks; the
/// sheet's own button still does all the writing. VoiceOver reads the mixed state as its value.
struct SelectAllRow: View {
    let state: SelectAllState
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                CheckboxGlyph(checked: state != .off, mixed: state == .mixed)
                Text(state == .on ? Strings.clearAll : Strings.selectAll)
                    .textStyle(Typography.bodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(state == .on ? [.isSelected] : [])
        .accessibilityValue(state == .mixed ? Strings.cdSelectAllSome : "")
    }
}
