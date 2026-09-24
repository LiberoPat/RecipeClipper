import Foundation

/// Finds the cooking time a step asks for ("simmer for 20 minutes", "bake 25 to 30 minutes",
/// "1 hour 30 minutes") so cook mode can offer a timer. Pure: string in, seconds out.
///
/// A range uses its lower bound, so the timer goes off in time to check. A step with no
/// stated duration ("whisk until smooth") returns nil and gets no timer: never guess one.
enum StepTimers {

    private static let unit = #"(hours?|hrs?|minutes?|mins?|seconds?|secs?)"#
    private static let qty = IngredientScaler.qty

    // groups: 1 quantity, 2 unit. An optional "-" allows "a 20-minute simmer".
    private static let duration = JRegex(
        #"(?<![\d.,/⁄])("# + qty + #")(?:\s*(?:[-–—]|to)\s*(?:"# + qty + #"))?\s*-?\s*"# + unit + #"\b"#,
        ignoreCase: true
    )

    // "1 hour 30 minutes", "2 minutes and 30 seconds". groups: 1 quantity, 2 unit
    private static let followOn = JRegex(
        #"^\s*(?:and\s+)?("# + qty + #")\s*-?\s*"# + unit + #"\b"#,
        ignoreCase: true
    )

    private static let maxSeconds = 24 * 3600

    static func parse(_ step: String) -> Int? {
        guard let first = duration.find(step) else { return nil }
        guard var total = toSeconds(first[1], first[2]) else { return nil }

        let rest = step.u16Substring(from: first.end)
        if let follow = followOn.find(rest) {
            let extra = toSeconds(follow[1], follow[2])
            // Only a smaller unit continues the duration ("1 hour" then "30 minutes").
            if let extra, unitSeconds(follow[2]) < unitSeconds(first[2]) {
                total = total &+ extra // Kotlin Int arithmetic wraps rather than trapping
            }
        }
        return (1...maxSeconds).contains(Int(total)) ? Int(total) : nil
    }

    private static func unitSeconds(_ unit: String) -> Int32 {
        switch unit.lowercased().first {
        case "h": return 3600
        case "m": return 60
        default: return 1
        }
    }

    /// Int32 with Kotlin's saturating `Double.toInt()`, so an absurd number can't trap.
    private static func toSeconds(_ quantity: String, _ unit: String) -> Int32? {
        guard let amount = IngredientScaler.parse(quantity) else { return nil }
        let seconds = amount * Double(unitSeconds(unit))
        if seconds.isNaN { return 0 }
        if seconds >= Double(Int32.max) { return Int32.max }
        if seconds <= Double(Int32.min) { return Int32.min }
        return Int32(seconds)
    }

    /// "20:00", or "1:05:00" from an hour up.
    static func clock(_ seconds: Int) -> String {
        let s = Swift.max(seconds, 0)
        if s >= 3600 {
            return String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        }
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    /// Short wording for a button: "20 min", "1 hr 30 min", "45 sec".
    static func label(_ seconds: Int) -> String {
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        let secs = seconds % 60
        if hours > 0 && minutes > 0 { return "\(hours) hr \(minutes) min" }
        if hours > 0 { return "\(hours) hr" }
        if minutes > 0 && secs > 0 { return "\(minutes) min \(secs) sec" }
        if minutes > 0 { return "\(minutes) min" }
        return "\(secs) sec"
    }
}
