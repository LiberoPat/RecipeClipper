import Foundation

/// The ingredient's name in an ingredient line ("2 large eggs, beaten" is "eggs"), for matching
/// a recipe's lines against the pantry (#46). Pure, and built from the scaler's, converter's
/// and density table's own parsing, so a name is read the way an amount and unit are.
///
/// It answers only "which ingredient", never "how much", and returns nil for anything it
/// doesn't understand (a heading, "salt and pepper", "juice of 1 lemon"), so a line it can't
/// name is never matched to the wrong thing. It reads the line with its recipe's language's
/// words (#14); a language the app has no words for gives no name. A port of the Kotlin
/// `IngredientName`.
enum IngredientName {

    // The words are shared with Android: shared/tables/<language>/names.json.
    private final class Words {
        let leadingWords: Set<String>
        let trailingWords: Set<String>
        let conjunctions: Set<String>
        // The words that may come before a shorter name and still mean it ("unsalted" butter),
        // longest first, so "extra virgin" is tried before "extra".
        let matchModifiers: [String]
        // " for dusting", " to taste": the name ends before the first one.
        let cut: JRegex

        init(_ words: LanguageWords) {
            leadingWords = Set(words.strings("names", "leadingWords"))
            trailingWords = Set(words.strings("names", "trailingWords"))
            conjunctions = Set(words.strings("names", "conjunctions"))
            var modifiers: [String] = []
            for m in words.strings("names", "matchModifiers") + words.strings("names", "leadingWords") where !modifiers.contains(m) {
                modifiers.append(m)
            }
            // Stable, like Kotlin's sortedByDescending, and by UTF-16 length like Kotlin's String.length.
            matchModifiers = modifiers.enumerated()
                .sorted { $0.element.u16Count != $1.element.u16Count ? $0.element.u16Count > $1.element.u16Count : $0.offset < $1.offset }
                .map(\.element)
            cut = JRegex(
                #"\s+"# + SharedTables.alternation(
                    words.strings("names", "cutPhrases").map { $0.replacingOccurrences(of: " ", with: #"\s+"#) }
                ) + "(?![A-Za-z])",
                ignoreCase: true
            )
        }
    }

    private static let whitespace = JRegex(#"\s+"#)

    /// The ingredient's name, lowercase ("unsalted butter"), or nil when there isn't one, or
    /// when `words` is nil (a language the app has no words for).
    static func of(_ line: String, words language: LanguageWords? = .english) -> String? {
        guard let language else { return nil }
        if line.kIsBlank || line.kTrimmed.hasSuffix(":") { return nil }
        // "☆醤油 大さじ1": the name comes first (#16).
        if IngredientScaler.patterns(language).amountAfterName { return TrailingAmount.nameOfLine(line, words: language) }
        let w = language.compiled(Words.self, Words.init)
        let scaler = IngredientScaler.patterns(language)
        let converter = UnitConverter.patterns(language)

        var text = line
        if let lead = scaler.leading.find(text) {
            text = text.u16Substring(from: lead.end)
            // "1-inch piece ginger": the number was a size, not an amount.
            if let size = scaler.notAnAmount.find(text) { text = text.u16Substring(from: size.end) }
            // Package sizes and alternate measures: "1 (14 oz) can", "1 cup (120 g) flour".
            text = IngredientDensities.stripParentheses(text)
            // "1 heaping cup flour": a size word can come before the unit.
            text = dropLeadingWords(text, w)
            if let unit = converter.unitAtStart.find(text) {
                text = text.u16Substring(from: unit.end)
                if let c = converter.continuationAtStart.find(text) { text = text.u16Substring(from: c.end) }
                if let s = converter.slashAtStart.find(text) { text = text.u16Substring(from: s.end) }
            }
        }
        text = dropLeadingWords(text, w)

        if let c = w.cut.find(text) { text = text.u16Substring(0, c.start) }

        let trailingModifiers = IngredientDensities.trailingModifiers(language)
        var words = IngredientDensities.headPhrase(text, words: language).split(separator: " ").map(String.init)
        while let last = words.last, w.trailingWords.contains(last) || trailingModifiers.contains(last) {
            words.removeLast()
        }
        let name = words.joined(separator: " ")
        return understood(name, language, w) ? name : nil
    }

    /// True when `a` and `b` name the same ingredient (#51): they're equal, or the longer ends
    /// with the shorter at a word boundary and every word before it is a plain modifier (the
    /// names table's `matchModifiers` or `leadingWords`). So "unsalted butter" matches "butter",
    /// while "rice flour", "butter beans" and "peanut butter" don't match "flour" or "butter", in
    /// either direction: a word the table doesn't know makes a different ingredient, and the
    /// line is Buy. Either may be a name from `of` or one a person typed.
    static func matches(_ a: String, _ b: String, words: LanguageWords = .english) -> Bool {
        let x = IngredientDensities.headPhrase(a, words: words)
        let y = IngredientDensities.headPhrase(b, words: words)
        if x.isEmpty || y.isEmpty { return false }
        let (longer, shorter) = x.u16Count >= y.u16Count ? (x, y) : (y, x)
        if !IngredientDensities.endsWithName(longer, shorter, spaced: words.spaced) { return false }
        return onlyModifiers(longer.u16Substring(0, longer.u16Count - shorter.u16Count).kTrimmed, words)
    }

    /// True when `text` is nothing but match modifiers, one after another.
    private static func onlyModifiers(_ text: String, _ language: LanguageWords) -> Bool {
        let modifiers = language.compiled(Words.self, Words.init).matchModifiers
        var rest = text
        while !rest.isEmpty {
            guard let modifier = modifiers.first(where: {
                rest == $0 || rest.hasPrefix(language.spaced ? $0 + " " : $0)
            }) else { return false }
            rest = rest.u16Substring(from: modifier.u16Count).kTrimmed
        }
        return true
    }

    /// Drops "large", "cloves", "pinch of": sizes, containers and cuts before the name.
    private static func dropLeadingWords(_ text: String, _ w: Words) -> String {
        let words = whitespace.split(text.kTrimmed).filter { !$0.isEmpty }
        var start = 0
        while start < words.count, w.leadingWords.contains(bare(words[start])) { start += 1 }
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
    private static func understood(_ name: String, _ language: LanguageWords, _ w: Words) -> Bool {
        if name.isEmpty || name.unicodeScalars.contains(where: { ("0"..."9").contains($0) }) { return false }
        var rest = name
        if let alias = IngredientDensities.aliasAtEnd(name, words: language), alias.contains(" "), name.hasSuffix(alias) {
            rest = String(name.dropLast(alias.count))
        }
        return !rest.split(separator: " ").contains { w.conjunctions.contains(String($0)) || $0.contains("/") }
    }
}
