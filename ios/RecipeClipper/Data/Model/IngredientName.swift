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
        // The listed singular/plural pairs (#191): each plural's singular, each singular's plural.
        let singular: [String: String]
        let plural: [String: String]
        // The words that may come before a shorter name and still mean it ("unsalted" butter),
        // longest first, so "extra virgin" is tried before "extra". Keyed like the names they
        // are compared with.
        let matchModifiers: [String]
        // " for dusting", " to taste": the name ends before the first one.
        let cut: JRegex

        init(_ words: LanguageWords) {
            leadingWords = Set(words.strings("names", "leadingWords"))
            trailingWords = Set(words.strings("names", "trailingWords"))
            conjunctions = Set(words.strings("names", "conjunctions"))
            let pairs = (words.table("names")["pluralPairs"] as? [[String]] ?? []).map { ($0[0], $0[1]) }
            var singular: [String: String] = [:]
            var plural: [String: String] = [:]
            for (one, many) in pairs {
                singular[many] = one
                plural[one] = many
            }
            self.singular = singular
            self.plural = plural
            var modifiers: [String] = []
            for m in (words.strings("names", "matchModifiers") + words.strings("names", "leadingWords")).map({ IngredientName.keyOf($0, singular) })
                where !modifiers.contains(m) {
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
    /// line is Buy. Either may be a name from `of` or one a person typed. A listed pair's words
    /// are the same in either number (`key`, #191): "onions" matches "onion", "red onions" "red
    /// onion", but "red onion" is still not "onion".
    static func matches(_ a: String, _ b: String, words: LanguageWords = .english) -> Bool {
        let x = key(IngredientDensities.headPhrase(a, words: words), words: words)
        let y = key(IngredientDensities.headPhrase(b, words: words), words: words)
        if x.isEmpty || y.isEmpty { return false }
        let (longer, shorter) = x.u16Count >= y.u16Count ? (x, y) : (y, x)
        if !IngredientDensities.endsWithName(longer, shorter, spaced: words.spaced) { return false }
        return onlyModifiers(longer.u16Substring(0, longer.u16Count - shorter.u16Count).kTrimmed, words)
    }

    /// `name` trimmed and lowercase, with every word the language lists as a plural (names.json
    /// `pluralPairs`, #191) in its singular, wherever it stands: "Red Onions" is "red onion",
    /// "pommes de terre" is "pomme de terre". Two names are one when their keys are equal. A word
    /// that isn't listed stays as it is, so nothing is inferred: "glass", "hummus" and "asparagus"
    /// are only themselves. With no `words`, only trimmed and lowercase.
    static func key(_ name: String, words: LanguageWords?) -> String {
        keyOf(name, words.map { $0.compiled(Words.self, Words.init).singular } ?? [:])
    }

    /// True when `a` and `b` are one name (`key`): trimmed, case aside, and a listed pair aside.
    static func same(_ a: String, _ b: String, words: LanguageWords?) -> Bool { key(a, words: words) == key(b, words: words) }

    /// `text` (a count's words, "onion, sliced") with each word in a listed pair worded for
    /// `count`: the plural above one, else the singular, keeping the word's capitals ("Zwiebel"
    /// for 3 is "Zwiebeln"). Every other word stays as written.
    static func counted(_ text: String, count: Double, words language: LanguageWords) -> String {
        let w = language.compiled(Words.self, Words.init)
        if w.plural.isEmpty { return text }
        let many = count > 1.0
        return word.replace(text) { m in
            let lower = m.value.lowercased()
            // Nil: not listed, or already in the right number.
            guard let target = many ? w.plural[lower] : w.singular[lower] else { return m.value }
            return withCapitals(target, like: m.value)
        }
    }

    // `word` with `like`'s capitals: all of them, or the first.
    private static func withCapitals(_ word: String, like: String) -> String {
        if like.u16Count > 1 && like == like.uppercased() { return word.uppercased() }
        if let first = like.first, first.isUppercase { return word.prefix(1).uppercased() + word.dropFirst() }
        return word
    }

    // A word in a name or a count's words: letters and their marks.
    private static let word = JRegex(#"[\p{L}\p{M}]+"#)

    fileprivate static func keyOf(_ name: String, _ singular: [String: String]) -> String {
        let text = name.kTrimmed.lowercased()
        if singular.isEmpty { return text }
        return word.replace(text) { singular[$0.value] ?? $0.value }
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
