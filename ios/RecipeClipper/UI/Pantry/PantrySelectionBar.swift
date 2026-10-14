import SwiftUI

/// The bar above the Pantry's list while selecting (#258; Android's PantrySelectionBar): ✕,
/// "N selected" (announced as it changes), Select all, then Mark run out and Remove, the primary
/// action.
struct PantrySelectionBar: View {
    let count: Int
    let allSelected: Bool
    let onExit: () -> Void
    let onSelectAll: () -> Void
    let onMarkRunOut: () -> Void
    let onRemove: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                Button(action: onExit) {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Palette.onBackground)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Strings.exitSelection)
                .accessibilityIdentifier("exitSelection")
                Text(Strings.pantrySelectedCount(count))
                    .textStyle(Typography.titleLarge)
                    .foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("selectedCount")
                Button(Strings.selectAll, action: onSelectAll)
                    .buttonStyle(TextActionStyle())
                    .disabled(allSelected)
                    .accessibilityIdentifier("selectAll")
            }
            HStack(spacing: 8) {
                Spacer(minLength: 0)
                Button(Strings.pantryMarkRunOut, action: onMarkRunOut)
                    .buttonStyle(OutlinedActionStyle())
                    .disabled(count == 0)
                    .opacity(count == 0 ? 0.4 : 1)
                    .accessibilityIdentifier("markRunOutSelected")
                Button(Strings.remove, action: onRemove)
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(count == 0)
                    .accessibilityIdentifier("removeSelected")
            }
            .padding(.bottom, 8)
            Hairline()
        }
        .padding(.horizontal, 12)
        .background(Palette.background)
        .onChange(of: count) { _, now in
            AccessibilityNotification.Announcement(Strings.pantrySelectedCount(now)).post()
        }
    }
}

/// Drag to select's geometry (#258): where each row is in the List (`space`), the part of the
/// List not under the bars, and where the finger is. A plain class, not observed, so the frames'
/// updates while scrolling never redraw the screen; the selection itself is the ViewModel's.
final class PantryRowFrames {
    static let space = "pantryList"

    var frames: [Int64: CGRect] = [:]
    var visible: ClosedRange<CGFloat> = 0...0
    var lastY: CGFloat = 0
    var scrolling: Task<Void, Never>?

    /// How close to the top or bottom a finger starts the list scrolling.
    private let edge: CGFloat = 60

    /// The row under `y`; nil over a heading or nothing.
    func id(at y: CGFloat) -> Int64? {
        frames.first { $0.value.minY <= y && y < $0.value.maxY }?.key
    }

    /// -1 near the top, 1 near the bottom, else 0.
    var edgeDirection: Int {
        if lastY < visible.lowerBound + edge { return -1 }
        if lastY > visible.upperBound - edge { return 1 }
        return 0
    }

    /// The row just past the last one showing in `direction`, to scroll to; nil at the end.
    func next(_ direction: Int, order: [Int64]) -> Int64? {
        let showing = frames.filter { $0.value.maxY > visible.lowerBound && $0.value.minY < visible.upperBound }.keys
        let indices = showing.compactMap { order.firstIndex(of: $0) }
        guard let edgeIndex = direction > 0 ? indices.max() : indices.min() else { return nil }
        let next = edgeIndex + direction
        return order.indices.contains(next) ? order[next] : nil
    }
}
