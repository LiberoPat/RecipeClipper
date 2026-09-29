import Combine
import Foundation
import Observation
import SwiftUI
import UIKit

/// Which way the bubble goes from its control. `auto`: wherever the system finds room.
enum TooltipSide {
    case auto, above, below
}

extension View {
    /// A screen that shows tooltips (#190). A visit starts when it appears and ends when it
    /// disappears. It reports which of its anchors are wholly on screen, and whether it's ready:
    /// past its first second, no keyboard up, and not `blocked` (a sheet, dialog or cover of its
    /// own, which a popover can't show over).
    func tooltipHost(_ screen: TooltipScreen, blocked: Bool = false) -> some View {
        modifier(TooltipHostModifier(screen: screen, blocked: blocked))
    }

    /// Marks the control `tooltip` points at, inside a `tooltipHost`; nothing outside one. A
    /// toolbar item is `inToolbar`: always on screen while its bar shows.
    func tooltipAnchor(_ tooltip: Tooltip, side: TooltipSide = .auto, inToolbar: Bool = false) -> some View {
        modifier(TooltipAnchorModifier(tooltip: tooltip, side: side, inToolbar: inToolbar))
    }
}

/// Where the anchors inside one host are, in the host's own coordinates (so a navigation
/// transition moving the whole screen changes nothing), and which are wholly on screen.
@MainActor
@Observable
final class TooltipAnchors {
    let token = UUID().uuidString
    /// The anchors wholly inside `viewport` (toolbar items: laid out at all).
    private(set) var visible: Set<Tooltip> = []
    @ObservationIgnored private var frames: [Tooltip: (frame: CGRect, inToolbar: Bool)] = [:]
    @ObservationIgnored var viewport: CGRect = .zero {
        didSet { refresh() }
    }

    func update(_ tooltip: Tooltip, frame: CGRect, inToolbar: Bool) {
        frames[tooltip] = (frame, inToolbar)
        refresh()
    }

    func remove(_ tooltip: Tooltip) {
        frames[tooltip] = nil
        refresh()
    }

    private func refresh() {
        let slack: CGFloat = 1
        let inside = Set(frames.compactMap { tooltip, anchor -> Tooltip? in
            let frame = anchor.frame
            guard frame.width > 0, frame.height > 0 else { return nil }
            if anchor.inToolbar { return tooltip }
            return viewport.insetBy(dx: -slack, dy: -slack).contains(frame) ? tooltip : nil
        })
        if inside != visible { visible = inside }
    }
}

private struct TooltipHostModifier: ViewModifier {
    let screen: TooltipScreen
    let blocked: Bool
    @Environment(TooltipsViewModel.self) private var tooltips: TooltipsViewModel?
    @State private var anchors = TooltipAnchors()
    @State private var appearance = 0
    @State private var settled = false
    @State private var keyboardUp = false

    private struct Report: Equatable {
        let visible: Set<Tooltip>
        let ready: Bool
    }

    func body(content: Content) -> some View {
        content
            .environment(anchors)
            // The part of the screen the content shows in: its own bounds, inside the bars (a
            // background would reach under them), in its own coordinates, as the anchors measure.
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
                anchors.viewport = CGRect(origin: .zero, size: size)
            }
            .coordinateSpace(.named(anchors.token))
            .onAppear {
                appearance += 1
                tooltips?.onVisit(token: anchors.token, screen: screen)
            }
            .onDisappear { tooltips?.onLeave(token: anchors.token) }
            // The recipe screen turning into cook mode, or back: another screen, another visit.
            .onChange(of: screen) { _, screen in
                appearance += 1
                tooltips?.onVisit(token: anchors.token, screen: screen)
            }
            // Nothing in a screen's first second: let it settle.
            .task(id: appearance) {
                settled = false
                try? await Task.sleep(for: .seconds(Tooltips.settleSeconds))
                if !Task.isCancelled { settled = true }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
                keyboardUp = true
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                keyboardUp = false
            }
            .onChange(of: Report(visible: anchors.visible, ready: settled && !keyboardUp && !blocked), initial: true) { _, report in
                tooltips?.onReport(token: anchors.token, visible: report.visible, ready: report.ready)
            }
    }
}

private struct TooltipAnchorModifier: ViewModifier {
    let tooltip: Tooltip
    let side: TooltipSide
    let inToolbar: Bool
    @Environment(TooltipsViewModel.self) private var tooltips: TooltipsViewModel?
    @Environment(TooltipAnchors.self) private var anchors: TooltipAnchors?
    /// Kept, so a screen coming back (whose geometry hasn't changed, so isn't reported again)
    /// puts its anchors back.
    @State private var frame: CGRect?

    func body(content: Content) -> some View {
        if let tooltips, let anchors {
            content
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(anchors.token)) } action: { frame in
                    self.frame = frame
                    anchors.update(tooltip, frame: frame, inToolbar: inToolbar)
                }
                .onAppear { if let frame { anchors.update(tooltip, frame: frame, inToolbar: inToolbar) } }
                .onDisappear { anchors.remove(tooltip) }
                .modifier(TooltipPopover(
                    isPresented: Binding(
                        get: { tooltips.uiState.current == tooltip && tooltips.uiState.token == anchors.token },
                        set: { if !$0 { tooltips.onClosed(tooltip) } }
                    ),
                    side: side
                ) {
                    TooltipBubble(tooltip: tooltip, mealPlan: tooltips.isOn(.mealPlan)) { tooltips.onDismiss(tooltip) }
                })
        } else {
            content
        }
    }
}

/// A popover, kept a popover on iPhone too. From iOS 18 it goes the side asked for (cook mode's
/// never over the current step's text); iOS 17 picks the side itself.
private struct TooltipPopover<Bubble: View>: ViewModifier {
    @Binding var isPresented: Bool
    let side: TooltipSide
    @ViewBuilder let bubble: () -> Bubble

    func body(content: Content) -> some View {
        if #available(iOS 18, *) {
            content.popover(isPresented: $isPresented, attachmentAnchor: .rect(.bounds), arrowEdge: arrowEdge) {
                bubble().presentationCompactAdaptation(.popover)
            }
        } else {
            content.popover(isPresented: $isPresented, attachmentAnchor: .rect(.bounds)) {
                bubble().presentationCompactAdaptation(.popover)
            }
        }
    }

    /// The popover's own edge the arrow sits on, so the opposite of the side asked for: `.bottom`
    /// puts the bubble above the control (seen on the iOS 27 simulator, `TooltipsUITests`).
    private var arrowEdge: Edge? {
        switch side {
        case .auto: nil
        case .above: .bottom
        case .below: .top
        }
    }
}

/// The bubble (#190): in the theme's inverse colours, in Karla, one button ("Got it" is its
/// visible label) that VoiceOver reads as it appears. Wider at the accessibility text sizes.
private struct TooltipBubble: View {
    let tooltip: Tooltip
    let mealPlan: Bool
    let onDismiss: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Button(action: onDismiss) {
            VStack(alignment: .leading, spacing: 6) {
                Text(Strings.tooltip(tooltip, mealPlan: mealPlan))
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.inverseOnSurface)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(Strings.tooltipGotIt)
                    .textStyle(Typography.labelLarge)
                    .foregroundStyle(Palette.inversePrimary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(width: typeSize.isAccessibilitySize ? 340 : 280)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(Strings.dismissTip)
        .accessibilityIdentifier("tooltip.\(tooltip.id)")
        .presentationBackground(Palette.inverseSurface)
    }
}
