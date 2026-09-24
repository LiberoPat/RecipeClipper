import Foundation

/// The ingredient's name in an ingredient line ("2 large eggs, beaten" is "eggs"), for matching
/// a recipe's lines against the pantry (#46). Pure, and built from the scaler's, converter's
/// and density table's own parsing, so a name is read the way an amount and unit are.
///
/// It answers only "which ingredient", never "how much", and returns nil for anything it
/// doesn't understand (a heading, "salt and pepper", "juice of 1 lemon"), so a line it can't
/// name is never matched to the wrong thing. A port of the Kotlin `IngredientName`.
enum IngredientName {

    // The words are shared with Android: shared/tables/en/names.json.
    private static let table = SharedTables.load("names")
    private static let leadingWords = Set(SharedTables.strings(table, "leadingWords"))
    private static let trailingWords = Set(SharedTables.strings(table, "trailingWords"))
    private static let conjunctions = Set(SharedTables.strings(table, "conjunctions"))

    // " for dusting", " to taste": the name ends before the first one.
    private static let cut = JRegex(
        #"\s+"# + SharedTables.alternation(
            SharedTables.strings(table, "cutPhrases").map { $0.replacingOccurrences(of: " ", with: #"\s+"#) }
        ) + "(?![A-Za-z])",
        ignoreCase: true
    )

    private static let whitespace = JRegex(#"\s+"#)

    /// The ingredient's name, lowercase ("unsalted butter"), or nil when there isn't one.
    static func of(_ line: String) -> String? {
        if line.kIsBlank || line.kTrimmed.hasSuffix(":") { return nil }

        var text = line
        if let lead = IngredientScaler.leading.find(text) {
            text = text.u16Substring(from: lead.end)
            // "1-inch piece ginger": the number was a size, not an amount.
            if let size = IngredientScaler.notAnAmount.find(text) { text = text.u16Substring(from: size.end) }
            // Package sizes and alternate measures: "1 (14 oz) can", "1 cup (120 g) flour".
            text = IngredientDensities.stripParentheses(text)
            // "1 heaping cup flour": a size word can come before the unit.
            text = dropLeadingWords(text)
            if let unit = UnitConverter.unitAtStart.find(text) {
                text = text.u16Substring(from: unit.end)
                if let c = UnitConverter.continuationAtStart.find(text) { text = text.u16Substring(from: c.end) }
                if let s = UnitConverter.slashAtStart.find(text) { text = text.u16Substring(from: s.end) }
            }
        }
        text = dropLeadingWords(text)

        if let c = cut.find(text) { text = text.u16Substring(0, c.start) }

        var words = IngredientDensities.headPhrase(text).split(separator: " ").map(String.init)
        while let last = words.last, trailingWords.contains(last) || IngredientDensities.trailingModifiers.contains(last) {
            words.removeLast()
        }
        let name = words.joined(separator: " ")
        return understood(name) ? name : nil
    }

    /// True when `a` and `b` name the same ingredient by the density table's rule: the longer
    /// ends with the shorter at a word boundary, so "unsalted butter" matches "butter" and
    /// "butter beans" doesn't. Either may be a name from `of` or one a person typed.
    static func matches(_ a: String, _ b: String) -> Bool {
        let x = IngredientDensities.headPhrase(a)
        let y = IngredientDensities.headPhrase(b)
        if x.isEmpty || y.isEmpty { return false }
        return x.u16Count >= y.u16Count ? IngredientDensities.endsWithName(x, y) : IngredientDensities.endsWithName(y, x)
    }

    /// Drops "large", "cloves", "pinch of": sizes, containers and cuts before the name.
    private static func dropLeadingWords(_ text: String) -> String {
        let words = whitespace.split(text.kTrimmed).filter { !$0.isEmpty }
        var start = 0
        while start < words.count, leadingWords.contains(bare(words[start])) { start += 1 }
        if start > 0, start < words.count, bare(words[start]) == "of" { start += 1 }
        return words.dropFirst(start).joined(separator: " ")
    }

    /// A word without the punctuation that can trail it in a line ("large," "pkg.").
    private static func bare(_ word: String) -> String {
        var w = word
        while let last = w.last, last == "," || last == "." || last == ";" { w.removeLast() }
        return w.lowercased()
    }

    // A digit left in the name ("juice of 1 lemon") or two ingredients ("salt and pepper") are
    // beyond a name. A conjunction inside a table alias ("half and half") is part of the name.
    private static func understood(_ name: String) -> Bool {
        if name.isEmpty || name.unicodeScalars.contains(where: { ("0"..."9").contains($0) }) { return false }
        var rest = name
        if let alias = IngredientDensities.aliasAtEnd(name), alias.contains(" "), name.hasSuffix(alias) {
            rest = String(name.dropLast(alias.count))
        }
        return !rest.split(separator: " ").contains { conjunctions.contains(String($0)) || $0.contains("/") }
    }
}
