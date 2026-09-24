import Foundation

/// How long ago something happened, as a shape rather than a sentence. The wording lives in
/// the UI's string catalog; keeping the decision here and the words there is what makes this
/// translatable, the same split `Servings.bareCount` uses.
enum Elapsed: Equatable {
    case justNow, minutes(Int), hours(Int), yesterday
    /// Two to six days. One day is `yesterday`, a week or more is `onDate`.
    case days(Int)
    /// A week or more ago, shown as a date rather than a count. Carries the original instant
    /// (epoch milliseconds).
    case onDate(Int64)
}

/// Pure: no wording, no formatting.
enum TimeAgo {
    static func since(_ then: Int64, now: Int64) -> Elapsed {
        let seconds = Swift.max((now - then) / 1000, 0)
        let minutes = seconds / 60
        let hours = minutes / 60
        let days = hours / 24
        if seconds < 60 { return .justNow }
        if minutes < 60 { return .minutes(Int(minutes)) }
        if hours < 24 { return .hours(Int(hours)) }
        if days == 1 { return .yesterday }
        if days < 7 { return .days(Int(days)) }
        return .onDate(then)
    }
}
