import Foundation

/// A recipe's prep, cook and total times as the app shows them (the times rule in CLAUDE.md's
/// parsing rules). Pure, so the parsers and the tour's sample (#179) share it. Android's
/// `Durations`, pinned by the differential corpus's `Dur` rows.
enum Durations {
    private static let isoDuration = JRegex(
        #"^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$"#,
        ignoreCase: true
    )

    /// A duration phrase in the recipe's words, as Condé Nast sites (Bon Appétit, Epicurious)
    /// publish instead of ISO: "20 minutes", "1 hour", "1 hour 30 minutes", "1 hr, 5 mins",
    /// "1 hour and 30 minutes". Whole-string only (used with matchEntire): whole numbers, an
    /// hours part and/or a minutes part, nothing else. A range ("1-2 hours", "20 to 25
    /// minutes"), a fraction or a word ("Overnight") does not match and is shown as written.
    /// Shared with Android: shared/tables/<language>/durations.json.
    private final class Patterns {
        let phrase: JRegex
        let hourSymbol: String
        let minuteSymbol: String

        init(_ words: LanguageWords) {
            let hours = SharedTables.alternation(words.strings("durations", "hours"))
            let minutes = SharedTables.alternation(words.strings("durations", "minutes"))
            let joiners = SharedTables.alternation(words.strings("durations", "joiners"))
            phrase = JRegex(
                #"\s*(?:(\d+)\s*"# + hours
                    + #"(?:\s*,?\s*(?:\b"# + joiners + #"\s+)?(\d+)\s*"# + minutes + ")?"
                    + #"|(\d+)\s*"# + minutes + #")\s*"#,
                ignoreCase: true
            )
            let table = words.table("durations")
            hourSymbol = table["hourSymbol"] as? String ?? ""
            minuteSymbol = table["minuteSymbol"] as? String ?? ""
        }
    }

    /// Turns an ISO-8601 duration like "PT1H30M", or a plain phrase in the recipe's words like
    /// "1 hour 30 minutes", into "1h 30m". Either one totalling zero ("PT0S", "P0D",
    /// "0 minutes") is nil, so the label is hidden rather than showing "PT0S". Anything else
    /// is returned as written (trimmed): never guess at "Overnight" or "20 to 25 minutes".
    /// With `words` nil (a language the app has no words for) only ISO is read, and written
    /// back with English's symbols.
    static func format(_ raw: String, words: LanguageWords? = .english) -> String? {
        let text = raw.kTrimmed
        if text.kIsBlank { return nil }
        let d = (words ?? .english).compiled(Patterns.self, Patterns.init)

        if let match = isoDuration.find(text), (1...4).contains(where: { !match[$0].kIsBlank }) {
            // Int32 with wrapping arithmetic, as Kotlin's Int: an overflowing figure reads as 0
            // (toIntOrNull) and an absurd total falls through to the raw text below.
            let days = Int32(match[1]) ?? 0
            let hours = Int32(match[2]) ?? 0
            let minutes = Int32(match[3]) ?? 0
            let seconds = Double(match[4]) ?? 0.0

            let secondMinutes = kotlinRoundToInt(seconds / 60.0)
            return renderMinutes(days &* 24 &* 60 &+ hours &* 60 &+ minutes &+ secondMinutes, asWritten: text, d)
        }

        if words == nil { return text }
        if let match = d.phrase.matchEntire(text) {
            // A figure too large for an Int is not a real time: show it as written.
            var hours: Int32 = 0
            if !match[1].isEmpty {
                guard let h = Int32(match[1]) else { return text }
                hours = h
            }
            let minutesText = match[2].isEmpty ? match[3] : match[2]
            var minutes: Int32 = 0
            if !minutesText.isEmpty {
                guard let m = Int32(minutesText) else { return text }
                minutes = m
            }
            return renderMinutes(hours &* 60 &+ minutes, asWritten: text, d)
        }

        return text
    }

    /// "1h 30m", "1h" or "20m"; nil for a zero total; `asWritten` for a total that
    /// overflowed to a negative number.
    private static func renderMinutes(_ totalMinutes: Int32, asWritten: String, _ d: Patterns) -> String? {
        if totalMinutes == 0 { return nil }
        let h = totalMinutes / 60
        let m = totalMinutes % 60
        if h > 0 && m > 0 { return "\(h)\(d.hourSymbol) \(m)\(d.minuteSymbol)" }
        if h > 0 { return "\(h)\(d.hourSymbol)" }
        if m > 0 { return "\(m)\(d.minuteSymbol)" }
        return asWritten
    }

    /// Kotlin's `Double.roundToInt()`: half rounds up (Math.round), saturating at Int bounds.
    private static func kotlinRoundToInt(_ x: Double) -> Int32 {
        let r = (x + 0.5).rounded(.down)
        if r >= Double(Int32.max) { return Int32.max }
        if r <= Double(Int32.min) { return Int32.min }
        return Int32(r)
    }
}
