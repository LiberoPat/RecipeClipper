import Combine
import Foundation
import Observation
import SwiftUI

struct TipsUiState: Equatable {
    /// The tips dismissed so far.
    var seen: Set<Tip> = []
}

/// The one-time tips (#151), for the whole app: the root puts one in the environment, so a
/// screen only names its `Tip` (`TipCallout`) and needs no ViewModel of its own. A tip for a
/// tab behind the `mealPlan` flag hides while the flag is off. Android's `TipsViewModel`.
@MainActor
@Observable
final class TipsViewModel {
    private(set) var uiState: TipsUiState
    @ObservationIgnored private let preferences: TourPreferences
    @ObservationIgnored private let flags: FeatureFlags
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()

    init(preferences: TourPreferences, flags: FeatureFlags) {
        self.preferences = preferences
        self.flags = flags
        // Read at once, so a dismissed tip never flashes.
        uiState = TipsUiState(seen: preferences.seenTips)
        preferences.seenTipsChanges
            .receive(on: DispatchQueue.main)
            .sink { [weak self] seen in self?.uiState.seen = seen }
            .store(in: &cancellables)
    }

    /// Whether `tip` shows: not dismissed, and its flag on. Reads the observable flags, so a
    /// view asking redraws when Developer settings changes one.
    func shows(_ tip: Tip) -> Bool {
        !uiState.seen.contains(tip) && (!tip.mealPlan || flags.isOn(.mealPlan))
    }

    func onDismiss(_ tip: Tip) {
        preferences.setTipSeen(tip, true)
        uiState.seen.insert(tip)
    }
}

/// `tip`'s one small callout, in place, until tapped: nothing once dismissed, or with no
/// `TipsViewModel` in the environment. It sits in the screen's flow, never over it, so it never
/// blocks what is under it. The whole card is one button for VoiceOver, labelled with its text,
/// with "Dismiss tip" as its hint; the × only shows that it closes.
struct TipCallout: View {
    let tip: Tip
    /// Space around the callout, applied only while it shows.
    var padding = EdgeInsets()
    @Environment(TipsViewModel.self) private var tips: TipsViewModel?

    var body: some View {
        if let tips, tips.shows(tip) {
            Button { tips.onDismiss(tip) } label: {
                HStack(alignment: .top, spacing: 8) {
                    Text(Strings.tip(tip))
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.onBackground)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "xmark")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Palette.muted)
                        .accessibilityHidden(true)
                }
                .padding(.leading, 14)
                .padding(.trailing, 10)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Palette.surfaceContainer))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Strings.dismissTip)
            .accessibilityIdentifier("tip.\(tip.rawValue)")
            .padding(padding)
        }
    }
}
