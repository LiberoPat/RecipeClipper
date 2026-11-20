import SwiftUI
import UniformTypeIdentifiers

/// Covers in rows of `columns`, every one a child of a single `ForEach`, so a cover keeps its
/// identity as it moves from one row to the next: the cook is holding one when that happens
/// (#381), and a view that is rebuilt mid-drag loses its drop target. Not lazy, so every cover
/// stays in the accessibility tree; each row is as tall as its tallest cover, which sits at its
/// top.
struct CoverGridLayout: Layout {
    var columns: Int
    var spacing: CGFloat = 12
    var rowSpacing: CGFloat = 18

    private func columnWidth(_ total: CGFloat) -> CGFloat {
        let gaps = spacing * CGFloat(columns - 1)
        return max(0, (total - gaps) / CGFloat(columns))
    }

    private func rowHeights(_ subviews: Subviews, width: CGFloat) -> [CGFloat] {
        stride(from: 0, to: subviews.count, by: columns).map { start in
            subviews[start..<min(start + columns, subviews.count)]
                .map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
                .max() ?? 0
        }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let total = proposal.width ?? 320
        let heights = rowHeights(subviews, width: columnWidth(total))
        return CGSize(width: total, height: heights.reduce(0, +) + rowSpacing * CGFloat(max(0, heights.count - 1)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let width = columnWidth(bounds.width)
        var y = bounds.minY
        for (row, height) in rowHeights(subviews, width: width).enumerated() {
            for column in 0..<columns {
                let index = row * columns + column
                guard index < subviews.count else { break }
                subviews[index].place(
                    at: CGPoint(x: bounds.minX + CGFloat(column) * (width + spacing), y: y),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(width: width, height: height)
                )
            }
            y += height + rowSpacing
        }
    }
}

/// Dropping a held cover. On a cover, entering it moves the held one to its place (`targetId`);
/// on the screen around the covers (`targetId` nil) a drop only ends the drag, so letting go
/// between two covers still keeps the order.
struct ListDropDelegate: DropDelegate {
    let targetId: Int64?
    let vm: ListsViewModel

    func validateDrop(info: DropInfo) -> Bool { MainActor.assumeIsolated { vm.uiState.draggingId != nil } }

    func dropEntered(info: DropInfo) {
        guard let targetId else { return }
        MainActor.assumeIsolated { vm.onDragOver(targetId) }
    }

    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }

    func performDrop(info: DropInfo) -> Bool {
        MainActor.assumeIsolated { vm.onDrop(on: targetId) }
        return true
    }
}

/// A press and hold lifts the cover (the system's own drag, with its haptic); the others slide
/// aside as it passes over them (#381). Taps are untouched until the hold. Off, the cover is
/// returned as it is.
struct ReorderableCover: ViewModifier {
    let enabled: Bool
    let listId: Int64
    let held: Bool
    let vm: ListsViewModel

    func body(content: Content) -> some View {
        if enabled {
            content
                .opacity(held ? 0.35 : 1)
                .onDrag {
                    vm.onDragStart(listId)
                    return NSItemProvider(object: "list-\(listId)" as NSString)
                }
                .onDrop(of: [.text], delegate: ListDropDelegate(targetId: listId, vm: vm))
        } else {
            content
        }
    }
}

/// VoiceOver's way to reorder (#381): Move earlier and Move later among a cover's actions, only
/// where the move is possible. A drag is hard to do with VoiceOver, so this is the real route.
struct MoveActions: ViewModifier {
    let earlier: (() -> Void)?
    let later: (() -> Void)?

    func body(content: Content) -> some View {
        content.accessibilityActions {
            if let earlier { Button(Strings.moveListEarlier, action: earlier) }
            if let later { Button(Strings.moveListLater, action: later) }
        }
    }
}
