import SwiftUI

/// The three counts under the title (#286; Android's PantryCounts): In stock, Running low and Run
/// out, each a box whose tap jumps to its first row (`onJump`, with that row's id). A zero is
/// off, and while selecting (#258) every box stays where it is but off, so the rows don't move
/// from under the finger; a box that is off is dimmed. Read as one each: "2, Run out".
struct PantryCounts: View {
    let counts: [PantryCount]
    let enabled: Bool
    let onJump: (Int64) -> Void

    var body: some View {
        HStack(spacing: 8) {
            ForEach(counts, id: \.stock) { count in
                let target = enabled ? count.firstId : nil
                Button { if let target { onJump(target) } } label: {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(verbatim: String(count.count))
                            .textStyle(Typography.titleLarge)
                            .foregroundStyle(color(count.stock))
                        Text(PantryRow.label(count.stock))
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(Palette.muted)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Palette.surfaceContainer))
                    .contentShape(RoundedRectangle(cornerRadius: 14))
                }
                // Not the default style, so in the List's row each box takes only its own taps.
                .buttonStyle(CountBoxStyle())
                .disabled(target == nil)
                .accessibilityIdentifier("pantryCount-\(count.stock)")
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    /// As the list shows each: Running low's Low tag, Run out's dimmed section.
    private func color(_ stock: PantryStock) -> Color {
        switch stock {
        case .inStock: return Palette.onBackground
        case .runningLow: return Palette.accentText
        case .runOut: return Palette.muted
        }
    }
}

/// A box that is off (a zero, or while selecting) is drawn at half strength, as on Android.
private struct CountBoxStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(!isEnabled ? 0.5 : configuration.isPressed ? 0.7 : 1)
    }
}

extension PantryListRow {
    /// Where a count's tap scrolls (#286): `id`'s row, or its section's heading when the row heads
    /// the section, so the heading shows too.
    static func scrollTarget(_ id: Int64, in sections: [PantrySection]) -> String? {
        for section in sections {
            guard let at = section.items.firstIndex(where: { $0.id == id }) else { continue }
            return at == 0 ? PantryListRow.heading(section).id : PantryListRow.item(section.items[at]).id
        }
        return nil
    }
}
