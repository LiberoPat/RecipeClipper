import Combine
import Foundation
import Observation

/// The tooltip showing now (#190), and the screen appearance (`token`) it shows on.
struct TooltipsUiState: Equatable {
    var current: Tooltip?
    var token: String?
}

/// The tooltips (#190), for the whole app: the root puts one in the environment, so a screen
/// only adds `.tooltipHost(_:)` and marks its controls with `.tooltipAnchor(_:)`. Which tooltip
/// shows is `Tooltips`' rule; this keeps the visit, what the screen reports is on it, and the
/// seen state. Android's `TooltipsViewModel`.
@MainActor
@Observable
final class TooltipsViewModel {
    private(set) var uiState = TooltipsUiState()
    @ObservationIgnored private let preferences: TourPreferences
    @ObservationIgnored private let flags: FeatureFlags
    @ObservationIgnored private var seen: Set<Tooltip>
    @ObservationIgnored private var visit: TooltipVisit?
    /// Each screen's last report, by its token: a screen still alive under another (pushed over
    /// it) may go on reporting, and must not overwrite the one on top.
    @ObservationIgnored private var reports: [String: (visible: Set<Tooltip>, ready: Bool)] = [:]
    /// The screens still alive, the latest last. A NavigationStack's root doesn't always hear
    /// `onDisappear` when a screen is pushed over it, nor `onAppear` when that screen goes, so
    /// leaving the top one makes the one under it the visit again, after its settling second.
    @ObservationIgnored private var live: [(token: String, screen: TooltipScreen)] = []
    @ObservationIgnored private var settling = false
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()

    init(preferences: TourPreferences, flags: FeatureFlags) {
        self.preferences = preferences
        self.flags = flags
        // Read at once, so a seen tooltip never flashes.
        seen = preferences.seenTooltips
        preferences.seenTooltipsChanges
            .receive(on: DispatchQueue.main)
            .sink { [weak self] seen in
                self?.seen = seen
                self?.update()
            }
            .store(in: &cancellables)
    }

    /// A screen appeared (`token` names it for as long as its view lives).
    func onVisit(token: String, screen: TooltipScreen) {
        live.removeAll { $0.token == token }
        live.append((token, screen))
        visit = Tooltips.visit(visit, token: token, screen: screen)
        update()
    }

    func onLeave(token: String) {
        live.removeAll { $0.token == token }
        reports[token] = nil
        let wasVisit = visit?.token == token
        visit = Tooltips.leave(visit, token: token)
        if wasVisit, visit == nil, let under = live.last {
            visit = Tooltips.visit(nil, token: under.token, screen: under.screen)
            settling = true
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(Tooltips.settleSeconds))
                self?.settling = false
                self?.update()
            }
        }
        update()
    }

    /// What the screen `token` has on it now: the anchors in view, and whether it's settled and uncovered.
    func onReport(token: String, visible: Set<Tooltip>, ready: Bool) {
        reports[token] = (visible, ready)
        update()
    }

    /// "Got it", or a tap on the bubble: seen for good, and nothing more this visit.
    func onDismiss(_ tooltip: Tooltip) {
        preferences.setTooltipSeen(tooltip, true)
        seen.insert(tooltip)
        visit = visit.map(Tooltips.closed)
        update()
    }

    /// The popover closed another way (a tap outside it): gone for this visit, still unseen.
    func onClosed(_ tooltip: Tooltip) {
        guard uiState.current == tooltip else { return }
        visit = visit.map(Tooltips.closed)
        update()
    }

    /// A flag, for the words a bubble picks (the recipe menu's without the meal plan).
    func isOn(_ flag: Flag) -> Bool { flags.isOn(flag) }

    /// Settings' "Show tips again": every tooltip shows once more, one at a time.
    func onReplay() {
        for tooltip in Tooltip.allCases { preferences.setTooltipSeen(tooltip, false) }
        seen = []
        update()
    }

    private func update() {
        let report = visit.flatMap { reports[$0.token] }
        let current = Tooltips.current(
            visit, seen: seen,
            isOn: { [flags] key in Flag(rawValue: key).map(flags.isOn) ?? true },
            visible: report?.visible ?? [],
            ready: report?.ready == true && !settling
        )
        // The tooltip picked is kept as the visit's, so another can't follow it in the same visit.
        if let current, let open = visit { visit = Tooltips.shown(open, current) }
        let state = TooltipsUiState(current: current, token: visit?.token)
        if state != uiState { uiState = state }
    }
}
