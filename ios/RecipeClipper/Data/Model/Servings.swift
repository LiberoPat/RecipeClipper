import Foundation

/// What a yield counts: people ("Serves 4", "4 servings") or things made ("Makes 16",
/// "24 cookies", "1 loaf"). Only changes the label word; the stepper and scaling are the same.
enum YieldKind { case serves, makes }

enum Servings {
    static let max = 99

    /// Matches a stated range ("4-6", "4 to 6"), which `pickYield` prefers.
    private static let range = JRegex(#"\d+\s*(?:[-–—]|"# + SharedTables.rangeWords + #")\s*\d+"#, ignoreCase: true)
    private static let firstNumber = JRegex(#"\d+"#)
    // The yield words are shared with Android: shared/tables/en/yield.json.
    private static let table = SharedTables.load("yield")
    private static let servingWord =
        JRegex(#"\b"# + SharedTables.alternation(SharedTables.strings(table, "serving")) + #"\b"#, ignoreCase: true)
    private static let makesWord =
        JRegex(#"\b"# + SharedTables.alternation(SharedTables.strings(table, "makes")) + #"\b"#, ignoreCase: true)
    /// A number followed by some other word: "24 cookies", "1 (9-inch) pie", "2 dozen".
    /// The lookahead skips the "to" of a range, so a bare "4 to 6" isn't read as a noun.
    private static let countedNoun = JRegex(#"\d[^\p{L}]*(?!"# + SharedTables.rangeWords + #"\b)\p{L}"#, ignoreCase: true)

    /// Sites often list several forms of the same yield, e.g. `["4", "4 to 6 servings"]`.
    /// Prefers the entry that states a range so "Original: 4-6 servings" isn't cut down
    /// to "4"; otherwise keeps the first entry.
    static func pickYield(_ candidates: [String]) -> String? {
        candidates.first { range.containsMatch(in: $0) } ?? candidates.first
    }

    /// Sites often publish just a number (`recipeYield: 6`). This only decides *whether* the
    /// yield is bare — the caller supplies the word via a plural string, since "6 servings"
    /// isn't just an `if` in every language. Text that already says what it is
    /// ("4 to 6 servings", "24 cookies") isn't bare, so the caller shows it as published.
    static func bareCount(_ recipeYield: String) -> Int? {
        let text = recipeYield.kTrimmed
        // Kotlin's `isDigit()` and `toIntOrNull()` both accept any Unicode decimal digit, one
        // UTF-16 char at a time: "٦" (Arabic-Indic) and "６" (full-width) are both 6 there.
        var value: Int32 = 0
        var any = false
        for scalar in text.unicodeScalars {
            guard scalar.value <= 0xFFFF, scalar.properties.generalCategory == .decimalNumber,
                  let digit = scalar.properties.numericValue else { return nil }
            // Int32, like Kotlin's `toIntOrNull()`: an absurdly long number is not a count.
            let (times, o1) = value.multipliedReportingOverflow(by: 10)
            let (sum, o2) = times.addingReportingOverflow(Int32(digit))
            if o1 || o2 { return nil }
            value = sum
            any = true
        }
        return any ? Int(value) : nil
    }

    /// Pulls the serving count out of a schema.org `recipeYield` string such as
    /// "4 servings", "Serves 4-6" or "Makes 24 cookies". Takes the first number.
    /// Returns nil when there is no usable number, which hides the scaling control.
    static func parse(_ recipeYield: String?) -> Int? {
        guard let recipeYield, let m = firstNumber.find(recipeYield) else { return nil }
        guard let first = Int32(m.value).map(Int.init) else { return nil }
        return (1...max).contains(first) ? first : nil
    }

    /// Whether the yield counts servings or things made, which picks "Serves" or "Makes" as
    /// the label. A serving word anywhere wins ("4 to 6 servings", "Makes 4 servings"); then
    /// "makes"/"yields", or a number followed by any other noun, means `.makes`. Anything else,
    /// including a bare number, stays `.serves` — the label the app always showed.
    static func kind(_ recipeYield: String?) -> YieldKind {
        let text = recipeYield?.kTrimmed ?? ""
        if text.isEmpty || bareCount(text) != nil { return .serves }
        if servingWord.containsMatch(in: text) { return .serves }
        if makesWord.containsMatch(in: text) || countedNoun.containsMatch(in: text) { return .makes }
        return .serves
    }
}
