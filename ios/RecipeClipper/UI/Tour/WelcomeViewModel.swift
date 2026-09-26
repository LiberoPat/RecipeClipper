import Foundation
import Observation

/// The welcome's cards (#151), in order. `weekly` only with the `mealPlan` flag on.
enum WelcomeCard: Equatable {
    case app, clip, daily, weekly
}

/// Where the welcome goes when it is done.
enum WelcomeExit: Equatable {
    case done
    case openRecipe(Int64)
}

struct WelcomeUiState: Equatable {
    var cards: [WelcomeCard] = [.app, .clip, .daily]
    var page = 0
    /// The daily card mentions Chef mode only with its flag on.
    var chefMode = false
    /// "Try it" is opening the sample.
    var opening = false
    /// Set once the welcome is done; the screen hands it to its caller.
    var exit: WelcomeExit?

    var card: WelcomeCard { cards[page] }
    var isLast: Bool { page == cards.count - 1 }
}

/// The first-run welcome (#151): three or four cards, skippable, the last offering the sample
/// recipe or Start. Every way out (Skip, Start, Try it) marks it seen. Opened from Settings'
/// "Show the tour again" (`again`), it shows every tip once more too. Android's
/// `WelcomeViewModel`.
@MainActor
@Observable
final class WelcomeViewModel {
    private(set) var uiState: WelcomeUiState
    @ObservationIgnored private let tour: FirstRunTour
    @ObservationIgnored private let language: () -> String?
    @ObservationIgnored private(set) var seeding: Task<Void, Never>?

    init(
        tour: FirstRunTour,
        flags: FeatureFlags,
        again: Bool,
        // The UI's language picks the sample's (English if it isn't written in it).
        language: @escaping () -> String? = { Bundle.main.preferredLocalizations.first }
    ) {
        self.tour = tour
        self.language = language
        uiState = WelcomeUiState(
            cards: [.app, .clip, .daily] + (flags.isOn(.mealPlan) ? [.weekly] : []),
            chefMode: flags.isOn(.chefMode)
        )
        if again { tour.replay() }
        // The first welcome ever adds the sample, so it is waiting in Recipes whichever way out.
        let lang = language()
        seeding = Task { await tour.addSampleOnce(language: lang) }
    }

    func onNext() { uiState.page = min(uiState.page + 1, uiState.cards.count - 1) }

    func onPrevious() { uiState.page = max(uiState.page - 1, 0) }

    /// Skip or Start.
    func onDone() {
        tour.finishWelcome()
        uiState.exit = .done
    }

    @discardableResult
    func onTrySample() -> Task<Void, Never>? {
        guard !uiState.opening, uiState.exit == nil else { return nil }
        uiState.opening = true
        let lang = language()
        return Task {
            // After the first showing's own add, so the two never race to add it twice.
            await seeding?.value
            let id = await tour.sampleToOpen(language: lang)
            tour.finishWelcome()
            uiState.opening = false
            uiState.exit = id.map(WelcomeExit.openRecipe) ?? .done
        }
    }
}
