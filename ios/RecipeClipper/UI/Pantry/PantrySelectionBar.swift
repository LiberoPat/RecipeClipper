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

/// The touch and hold that starts selecting, and drag to select (#258), as UIKit recognisers on
/// the List's scroll view: a SwiftUI gesture on a row stopped the List's swipe actions, and
/// `EditMode`'s own multi-select can't carry a touch and hold on into a drag. A touch and hold on
/// a row starts selecting ([onLongPress]) and, without lifting, drags; while selecting, a pan
/// that starts on a row's checkbox column drags at once (the scroll view's pan waits for it to
/// fail, which it does at once anywhere else, so a plain drag still scrolls). Each move reports
/// the row under the finger ([onDragTo]); near the top or bottom edge the list scrolls on its
/// own. Only the geometry lives here: the rows' frames on screen, kept by the screen, in a plain
/// class so their updates never redraw it. The selection itself is the ViewModel's.
@MainActor
final class PantryDragSelect: NSObject, UIGestureRecognizerDelegate {
    var frames: [Int64: CGRect] = [:]
    var isSelecting: () -> Bool = { false }
    var onLongPress: (Int64) -> Void = { _ in }
    var onDragStart: (Int64) -> Void = { _ in }
    var onDragTo: (Int64) -> Void = { _ in }
    var onDragEnd: () -> Void = {}
    /// Where the selection bar laid over the List ends, on screen: the drag's top edge is below it.
    var coveredTop: CGFloat = 0

    /// The checkbox column's width, from the row's leading edge.
    private let checkboxWidth: CGFloat = 44
    /// How close to the top or bottom a finger starts the list scrolling, and its fastest step.
    private let edge: CGFloat = 64
    private let maxStep: CGFloat = 14

    private weak var scrollView: UIScrollView?
    private weak var checkboxPan: UIPanGestureRecognizer?
    private var panStart: Int64?
    private var lastY: CGFloat = 0
    private var timer: Timer?

    /// The row under `y` (window coordinates, as SwiftUI's global space); nil over anything else.
    func id(at y: CGFloat) -> Int64? {
        frames.first { $0.value.minY <= y && y < $0.value.maxY }?.key
    }

    /// Adds the recognisers to the scroll view holding `view`, once.
    func attach(to view: UIView) {
        var candidate = view.superview
        while let current = candidate, !(current is UIScrollView) { candidate = current.superview }
        guard let scroll = candidate as? UIScrollView, scroll !== scrollView else { return }
        scrollView = scroll
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(held(_:)))
        hold.minimumPressDuration = 0.4
        hold.delegate = self
        scroll.addGestureRecognizer(hold)
        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        pan.delegate = self
        scroll.addGestureRecognizer(pan)
        scroll.panGestureRecognizer.require(toFail: pan)
        checkboxPan = pan
    }

    func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
        if recognizer === checkboxPan, let pan = checkboxPan {
            // Where the finger went down: the pan only begins once it has moved.
            let now = pan.location(in: nil)
            let moved = pan.translation(in: nil)
            let start = CGPoint(x: now.x - moved.x, y: now.y - moved.y)
            guard isSelecting(), let id = id(at: start.y), let frame = frames[id], start.x <= frame.minX + checkboxWidth else {
                return false
            }
            panStart = id
            return true
        }
        return id(at: recognizer.location(in: nil).y) != nil
    }

    /// Alongside the rows' own (SwiftUI's) recognisers, which would otherwise keep these from ever
    /// beginning; never alongside the scroll view's pan, so a drag that has begun doesn't scroll.
    func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        other !== scrollView?.panGestureRecognizer
    }

    /// Whether a row's tap now is the release of a touch and hold, not a tap.
    var ignoresTap: Bool { Date() < ignoreTapsUntil }
    private var ignoreTapsUntil = Date.distantPast

    @objc private func held(_ recognizer: UILongPressGestureRecognizer) {
        let y = recognizer.location(in: nil).y
        switch recognizer.state {
        case .began:
            guard let id = id(at: y) else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            ignoreTapsUntil = .distantFuture
            lastY = y
            onLongPress(id)
        case .changed:
            track(y)
        case .ended, .cancelled, .failed:
            ignoreTapsUntil = Date().addingTimeInterval(0.5)
            finish()
        default:
            break
        }
    }

    @objc private func panned(_ recognizer: UIPanGestureRecognizer) {
        let y = recognizer.location(in: nil).y
        switch recognizer.state {
        case .began:
            guard let id = panStart else { return }
            onDragStart(id)
            track(y)
        case .changed:
            track(y)
        case .ended, .cancelled, .failed:
            finish()
        default:
            break
        }
    }

    private func track(_ y: CGFloat) {
        lastY = y
        if let id = id(at: y) { onDragTo(id) }
        if step() != 0, timer == nil {
            timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 60, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.tick() }
            }
        }
    }

    private func finish() {
        timer?.invalidate()
        timer = nil
        panStart = nil
        onDragEnd()
    }

    /// How far to scroll this frame: faster the nearer the edge, 0 away from both.
    private func step() -> CGFloat {
        guard let scroll = scrollView else { return 0 }
        let frame = scroll.convert(scroll.bounds, to: nil)
        let top = max(frame.minY + scroll.adjustedContentInset.top, coveredTop)
        let bottom = frame.maxY - scroll.adjustedContentInset.bottom
        if lastY < top + edge { return -maxStep * min(1, (top + edge - lastY) / edge) }
        if lastY > bottom - edge { return maxStep * min(1, (lastY - (bottom - edge)) / edge) }
        return 0
    }

    private func tick() {
        let step = step()
        guard let scroll = scrollView, step != 0 else {
            timer?.invalidate()
            timer = nil
            return
        }
        let lowest = -scroll.adjustedContentInset.top
        let highest = max(lowest, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
        scroll.contentOffset.y = min(highest, max(lowest, scroll.contentOffset.y + step))
        if let id = id(at: lastY) { onDragTo(id) }
    }

    /// A zero-size view inside the List that hands its scroll view to the controller.
    struct Installer: UIViewRepresentable {
        let controller: PantryDragSelect

        func makeUIView(context: Context) -> InstallerView {
            let view = InstallerView()
            view.controller = controller
            view.isUserInteractionEnabled = false
            return view
        }

        func updateUIView(_ view: InstallerView, context: Context) { view.controller = controller }
    }

    final class InstallerView: UIView {
        weak var controller: PantryDragSelect?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { controller?.attach(to: self) }
        }
    }
}
