import Foundation

/// Whether the Today card (#402) may show, given the cook's choices (#448): "Show today's meal" in
/// Settings' Plan section (`showSetting`) and the day its close button was last tapped
/// (`dismissedDay`, an epoch day, local; nil when never). A close lasts for the rest of that day
/// only, so the card returns the next day; it is per day, never per meal. `closable` is the
/// `todayCardClose` flag: off, neither choice counts and the card behaves as before. The mid-cook
/// Continue cooking card is not this card and never asks. Pure, and identical to Android's
/// `TodayCardChoice`.
enum TodayCardChoice {

    /// Whether the Today card shows on `today` (an epoch day, local).
    static func shows(closable: Bool, showSetting: Bool, dismissedDay: Int64?, today: Int64) -> Bool {
        !closable || (showSetting && dismissedDay != today)
    }
}
