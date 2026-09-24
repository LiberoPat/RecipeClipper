import Foundation

/// What a yield counts: people ("Serves 4", "4 servings") or things made ("Makes 16",
/// "24 cookies", "1 loaf"). Only changes the label word; the stepper and scaling are the same.
enum YieldKind { case serves, makes }

enum Servings {
    static let max = 99

    /// One language's yield words: shared/tables/<language>/yield.json and ranges.json.
    private final class Patterns {
        /// Matches a stated range ("4-6", "4 to 6"), which `pickYield` prefers.
        let range: JRegex
        let servingWord: JRegex
        let makesWord: JRegex
        /// A number followed by some other word: "24 cookies", "1 (9-inch) pie", "2 dozen".
        /// The lookahead skips the "to" of a range, so a bare "4 to 6" isn't read as a noun.
        let countedNoun: JRegex

        init(_ words: LanguageWords) {
            range = JRegex(#"\d+\s*(?:[-–—]|"# + words.rangeWords + #")\s*\d+"#, ignoreCase: true)
            // Whole words by letters rather than \b, which the JDK, Android's ICU and iOS read
            // differently beside accented letters ("porções", #15).
            servingWord = JRegex(#"(?<!\p{L})"# + SharedTables.alternation(words.strings("yield", "serving")) + #"(?!\p{L})"#, ignoreCase: true)
            makesWord = JRegex(#"(?<!\p{L})"# + SharedTables.alternation(words.strings("yield", "makes")) + #"(?!\p{L})"#, ignoreCase: true)
            countedNoun = JRegex(#"\d[^\p{L}]*(?!"# + words.rangeWords + #"(?!\p{L}))\p{L}"#, ignoreCase: true)
        }
    }

    private static func patterns(_ words: LanguageWords) -> Patterns { words.compiled(Patterns.self, Patterns.init) }

    /// A range in a language the app has no words for: the dashes are anyone's.
    private static let dashRange = JRegex(#"\d+\s*[-–—]\s*\d+"#)
    private static let firstNumber = JRegex(#"\d+"#)

    /// Sites often list several forms of the same yield, e.g. `["4", "4 to 6 servings"]`.
    /// Prefers the entry that states a range so "Original: 4-6 servings" isn't cut down
    /// to "4"; otherwise keeps the first entry.
    static func pickYield(_ candidates: [String], words: LanguageWords? = .english) -> String? {
        let range = words.map { patterns($0).range } ?? dashRange
        return candidates.first { range.containsMatch(in: $0) } ?? candidates.first
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
    /// Returns nil when there is no usable number, which hides the scaling control, and for a
    /// language the app has no words for (`words` nil), whose lines couldn't be scaled.
    static func parse(_ recipeYield: String?, words: LanguageWords? = .english) -> Int? {
        guard words != nil, let recipeYield, let m = firstNumber.find(recipeYield) else { return nil }
        guard let first = Int32(m.value).map(Int.init) else { return nil }
        return (1...max).contains(first) ? first : nil
    }

    /// Whether the yield counts servings or things made, which picks "Serves" or "Makes" as
    /// the label. A serving word anywhere wins ("4 to 6 servings", "Makes 4 servings"); then
    /// "makes"/"yields", or a number followed by any other noun, means `.makes`. Anything else,
    /// including a bare number, stays `.serves` — the label the app always showed.
    static func kind(_ recipeYield: String?, words: LanguageWords? = .english) -> YieldKind {
        let text = recipeYield?.kTrimmed ?? ""
        guard !text.isEmpty, bareCount(text) == nil, let words else { return .serves }
        let p = patterns(words)
        if p.servingWord.containsMatch(in: text) { return .serves }
        if p.makesWord.containsMatch(in: text) || p.countedNoun.containsMatch(in: text) { return .makes }
        return .serves
    }
}
