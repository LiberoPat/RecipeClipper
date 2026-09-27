import Foundation

/// `gramsPerCup` is per US cup (236.6 ml); nil means "recognised, but deliberately not
/// converted" (ingredients whose weight varies too much to state a number honestly).
/// `liquid` means pourable, and is what the "convert liquids too" option controls.
/// `stickable` allows the "stick" unit (butter and margarine only).
struct Density: Equatable {
    let gramsPerCup: Double?
    let liquid: Bool
    var stickable: Bool = false
}

/// Approximate weights of common baking ingredients. Dry goods follow King Arthur Baking's
/// published ingredient weight chart (spooned-and-levelled cups); liquids and fats use
/// physical densities (USDA), so a cup of milk is 245 g rather than a rounded 227 g.
///
/// Only ingredients that weigh roughly the same every time belong here. Salt (table vs
/// kosher differ ~2x), chopped produce, shredded cheese, nuts, rolled oats and rice
/// (cooked vs raw) are left out on purpose, so those lines stay as written.
enum IngredientDensities {

    private struct Entry {
        let aliases: [String]
        let density: Density
    }

    // The table is shared with Android: shared/tables/<language>/densities.json. A null
    // gramsPerCup is a skip entry, which matches by name but converts nothing, beating a shorter
    // alias like plain "flour".
    private final class Table {
        let aliases: [(alias: String, density: Density)]
        let trailingModifiers: Set<String>

        init(_ words: LanguageWords) {
            let table = words.table("densities")
            let entries: [Entry] = SharedTables.objects(table, "entries").map { e in
                Entry(
                    aliases: SharedTables.strings(e, "aliases"),
                    density: Density(
                        gramsPerCup: (e["gramsPerCup"] as? NSNumber)?.doubleValue,
                        liquid: e["liquid"] as? Bool ?? false,
                        stickable: e["stickable"] as? Bool ?? false
                    )
                )
            }

            // Longest alias first, so "brown sugar" wins over "sugar" and "peanut butter" over
            // "butter". Ties keep table order (Kotlin's sortedByDescending is stable; the index
            // makes it so here).
            aliases = entries
                .flatMap { entry in entry.aliases.map { (alias: $0, density: entry.density) } }
                .enumerated()
                .sorted { a, b in
                    a.element.alias.count != b.element.alias.count
                        ? a.element.alias.count > b.element.alias.count
                        : a.offset < b.offset
                }
                .map { $0.element }

            trailingModifiers = Set(SharedTables.strings(table, "trailingModifiers"))
        }
    }

    private static let innermostParens = JRegex(#"\([^()]*\)"#)
    private static let whitespace = JRegex(#"\s+"#)

    /// Looks the ingredient up by the *end* of its name, so "unsalted butter" and "light
    /// brown sugar" match while "butter beans" and "flour tortillas" don't.
    static func find(_ ingredientText: String, words: LanguageWords = .english) -> Density? {
        let table = table(words)
        let phrase = headPhrase(ingredientText, table.trailingModifiers)
        return table.aliases.first { endsWithName(phrase, $0.alias, spaced: words.spaced) }?.density
    }

    /// The longest alias `phrase` (a head phrase) ends in, as `find` matches it; nil if none.
    static func aliasAtEnd(_ phrase: String, words: LanguageWords = .english) -> String? {
        table(words).aliases.first { endsWithName(phrase, $0.alias, spaced: words.spaced) }?.alias
    }

    /// The words dropped from the end of a name before matching ("packed", "melted").
    static func trailingModifiers(_ words: LanguageWords = .english) -> Set<String> { table(words).trailingModifiers }

    private static func table(_ words: LanguageWords) -> Table { words.compiled(Table.self, Table.init) }

    /// True when `phrase` is `name` or ends with it at a word boundary: the table's matching rule.
    /// Where the language isn't `spaced` (Japanese, #16) any character is a boundary: "有塩バター"
    /// is "バター".
    static func endsWithName(_ phrase: String, _ name: String, spaced: Bool = true) -> Bool {
        phrase == name || phrase.hasSuffix(spaced ? " " + name : name)
    }

    /// Removes parenthesised text, including nested or doubled parentheses ("((all-purpose
    /// flour))"), innermost first until nothing changes, then drops any unmatched paren.
    static func stripParentheses(_ text: String) -> String {
        var current = text
        while true {
            let next = innermostParens.replace(current, with: " ")
            if next == current { break }
            current = next
        }
        return current.replacingOccurrences(of: "(", with: " ").replacingOccurrences(of: ")", with: " ")
    }

    /// The ingredient name: text before the first comma, without parentheses or modifiers.
    static func headPhrase(_ text: String, words: LanguageWords = .english) -> String {
        headPhrase(text, table(words).trailingModifiers)
    }

    private static func headPhrase(_ text: String, _ trailingModifiers: Set<String>) -> String {
        var s = stripParentheses(text)
        if let comma = s.firstIndex(of: ",") { s = String(s[..<comma]) }
        s = s.lowercased()
            .replacingOccurrences(of: "'", with: "")
            .replacingOccurrences(of: "’", with: "")
            .replacingOccurrences(of: "-", with: " ")
        var words = whitespace.split(s).filter { !$0.isEmpty }
        while let last = words.last, trailingModifiers.contains(last) { words.removeLast() }
        return words.joined(separator: " ")
    }
}
