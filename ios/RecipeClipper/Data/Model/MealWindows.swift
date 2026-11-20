import Foundation

/// When a planned meal is still worth offering on Home's Today card (#402), by the meal type's
/// built-in key: breakfast until 11:00, lunch until 15:00, dinner and snack until the end of the
/// day. The cook's own meal types (no key) have no window. A meal whose window has passed drops
/// off the card entirely, cooked or not. Times are local, as millis since local midnight. Pure,
/// and identical to Android's `MealWindows`.
enum MealWindows {

    static let millisPerHour: Int64 = 3_600_000

    /// Breakfast leads until 11:00.
    static let breakfastEnds = 11 * millisPerHour

    /// Lunch leads until 15:00.
    static let lunchEnds = 15 * millisPerHour

    /// When the window of the meal type with `builtInKey` ends; nil for none before the day's end.
    static func endsAt(_ builtInKey: String?) -> Int64? {
        switch builtInKey {
        case "breakfast": return breakfastEnds
        case "lunch": return lunchEnds
        default: return nil
        }
    }

    /// Whether a meal of type `builtInKey` is still offered at `millisOfDay` (a window ends exclusively).
    static func isOpen(_ builtInKey: String?, millisOfDay: Int64) -> Bool {
        endsAt(builtInKey).map { millisOfDay < $0 } ?? true
    }

    /// How many windows have ended by `millisOfDay`: the card only changes when this does.
    static func ended(_ millisOfDay: Int64) -> Int {
        [breakfastEnds, lunchEnds].filter { $0 <= millisOfDay }.count
    }

    /// Millis from `millisOfDay` until the next window ends today; nil when none is left.
    static func untilNextEnd(_ millisOfDay: Int64) -> Int64? {
        [breakfastEnds, lunchEnds].first { $0 > millisOfDay }.map { $0 - millisOfDay }
    }
}
