import Foundation

/// A group of fresh ingredients that go off within days (#396): `key` is the same in every
/// language's table, `minDays` and `maxDays` the "keeps about N days once bought" hint's range.
/// The numbers are a hint, never a date the app claims to know.
struct PerishableGroup: Equatable {
    let key: String
    let minDays: Int
    let maxDays: Int

    /// The range as the screen says it: "1-2", or "3" for a group whose two ends are equal.
    var keeps: String { minDays == maxDays ? "\(minDays)" : "\(minDays)-\(maxDays)" }
}

/// An ingredient that matched a `PerishableGroup`; `name` is the ingredient's name, as `IngredientName.of` reads it.
struct Perishable: Equatable {
    let group: PerishableGroup
    let name: String
}

/// The perishables table (#396, `shared/tables/<language>/perishables.json`; Android's
/// `Perishables`): which ingredients are fresh food that keeps only days once bought. Matches by
/// the **end** of the ingredient's name, longest alias first, exactly as `IngredientDensities`
/// does ("smoked salmon" is a `skip` entry, which beats "salmon" by being longer; "fish sauce"
/// never ends in "fish"). A listed singular and plural are one word (`IngredientName.key`). A name
/// the table doesn't list, or a line the app can't name, is not perishable as far as the app
/// says, so it is bought now: the failure is the cook buying early, never an unsure claim about
/// the food.
enum Perishables {

    /// A meal at least this many days from today is far enough away for its perishable
    /// ingredients to wait for a second shop (Shop for this week, #396). Four is at or past the
    /// low end (`minDays`) of every group's range, so a meal four or more days out is one a shop
    /// made today may already have outlasted; a nearer one is bought now, with everything else.
    static let laterDays = 4

    private final class Table {
        let groups: [PerishableGroup]
        /// Openings that make a name a product made from the food, not the food ("caldo de", "confiture de").
        let skipPrefixes: [String]
        /// Longest alias first; a skip entry has a nil group. Ties keep the file's order, skips first.
        let aliases: [(alias: String, group: PerishableGroup?)]

        init(_ words: LanguageWords) {
            let table = words.table("perishables")
            var groups: [PerishableGroup] = []
            var entries: [(alias: String, group: PerishableGroup?)] = SharedTables.strings(table, "skip").map { ($0, nil) }
            for g in SharedTables.objects(table, "groups") {
                let group = PerishableGroup(
                    key: g["key"] as? String ?? "",
                    minDays: (g["minDays"] as? NSNumber)?.intValue ?? 0,
                    maxDays: (g["maxDays"] as? NSNumber)?.intValue ?? 0
                )
                groups.append(group)
                entries += SharedTables.strings(g, "aliases").map { ($0, group) }
            }
            self.groups = groups
            skipPrefixes = SharedTables.strings(table, "skipPrefixes").map { IngredientName.key($0, words: words) }
            aliases = entries
                .map { (alias: IngredientName.key($0.alias, words: words), group: $0.group) }
                .enumerated()
                .sorted { a, b in
                    a.element.alias.u16Count != b.element.alias.u16Count
                        ? a.element.alias.u16Count > b.element.alias.u16Count
                        : a.offset < b.offset
                }
                .map { $0.element }
        }
    }

    private static func table(_ words: LanguageWords) -> Table { words.compiled(Table.self, Table.init) }

    /// Every group of `words`'s table, in file order.
    static func groups(_ words: LanguageWords = .english) -> [PerishableGroup] { table(words).groups }

    /// The group an ingredient `name` (`IngredientName.of`'s answer, or a typed one) belongs to,
    /// or nil when it matches none, or matches a skip entry. Nil `words` (a language the app has
    /// no words for) matches nothing.
    static func groupOf(_ name: String, words: LanguageWords?) -> PerishableGroup? {
        guard let words else { return nil }
        let table = table(words)
        let phrase = IngredientName.key(IngredientDensities.headPhrase(name, words: words), words: words)
        if phrase.isEmpty { return nil }
        if table.skipPrefixes.contains(where: { phrase.hasPrefix($0 + " ") }) { return nil }
        return table.aliases.first { IngredientDensities.endsWithName(phrase, $0.alias, spaced: words.spaced) }?.group
    }

    /// The perishable an ingredient `line` is, by the name `IngredientName.of` reads from it; nil for none.
    static func find(_ line: String, words: LanguageWords?) -> Perishable? {
        guard let words, let name = IngredientName.of(line, words: words) else { return nil }
        return groupOf(name, words: words).map { Perishable(group: $0, name: name) }
    }

    /// The most delicate perishable among `lines`: the shortest-keeping group (by `maxDays`, then
    /// `minDays`); the first line wins a tie. Nil when none is perishable.
    static func mostDelicate(_ lines: [String], words: LanguageWords?) -> Perishable? {
        var best: Perishable?
        for found in lines.compactMap({ find($0, words: words) }) {
            guard let current = best else { best = found; continue }
            if (found.group.maxDays, found.group.minDays) < (current.group.maxDays, current.group.minDays) { best = found }
        }
        return best
    }
}
