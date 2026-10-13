import Foundation

/// What on-device text recognition gives for a fraction glyph it misreads (#245), measured on
/// `shared/fixtures/reddit/photos/card-fractions-*.jpg` and listed in `photo-fractions.json`:
/// "%" for ⅔, "&" for ⅛, "/4" for ¾, "Ys" for ⅓. Which fraction it was can't be told ("%" was ⅔
/// there, and may be ½ or ¾ elsewhere), so it is never replaced by itself: the photo review
/// (PhotoTextSorter) lists the line with fraction chips for the cook, and the scaler and
/// converter leave a line holding one as written. Language-independent; the unit after it is
/// the line's language's. The Kotlin `FractionLookAlike` is the reference.
enum FractionLookAlike {

    private static let table = SharedTables.read("photo-fractions")
    private static let afterNumberOnly = SharedTables.strings(table, "afterNumberOnly")

    /// The fractions offered to replace a look-alike, in the order shown.
    static let fractions = SharedTables.strings(table, "fractions")

    // A look-alike at the start, or after a whole number at the start, ending a word.
    // Groups: 1 the space before, 2 the whole number, 3 the space after it, 4 the look-alike.
    private static let slotPattern = JRegex(
        #"^(\s*)(?:(\d+)(\s*))?("# +
            SharedTables.alternation(
                (SharedTables.strings(table, "lookAlikes") + afterNumberOnly)
                    .sorted { $0.count > $1.count }.map { NSRegularExpression.escapedPattern(for: $0) }
            ) + #")(?![\p{L}\d])"#
    )

    /// Where `line`'s amount holds a look-alike: at the start ("% cup sugar") or after a whole
    /// number there ("2% cups oats", "1 a cups flour"), and then only before a unit
    /// (`unitAtStart`, the scaler's), unless it is a symbol at the very start ("& tsp", "/4 tsp").
    /// So "2% milk" and "70% chocolate" are percentages, and "1/2 cup" a fraction. Nil if none.
    static func slot(_ line: String, unitAtStart: JRegex) -> NSRange? {
        guard let m = slotPattern.find(line) else { return nil }
        let token = m[4]
        let afterNumber = !m[2].isEmpty
        if !afterNumber && afterNumberOnly.contains(token) { return nil }
        // "1/2" is a fraction; only "1 /2" has lost one.
        if afterNumber && m[3].isEmpty && token.hasPrefix("/") { return nil }
        let unitNext = unitAtStart.containsMatch(in: line.u16Substring(from: m.end))
        let symbol = !token.contains { $0.isLetter }
        return unitNext || (symbol && !afterNumber) ? m.ranges[4] : nil
    }
}
