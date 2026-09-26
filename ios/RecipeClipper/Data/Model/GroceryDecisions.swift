import Foundation

/// The on-device model's help with the grocery list (#99, over #104's `DecisionRule`), ported
/// from the Kotlin `GroceryDecisions` (see there). The model never writes a number: an answer
/// only regroups lines or drops trailing text; `GroceryCombiner`'s exact rules decide totals.
enum GroceryDecisions {

    /// A line cut into its `core` ("2 eggs") and the `trailing` text after the name (", beaten").
    struct Split: Equatable {
        let core: String
        let trailing: String
    }

    private static let separator = JRegex(#"[,;(]|\s[-–—]+(?=\s|$)"#)

    /// The line cut at the first comma, semicolon, bracket or dash whose left side has an
    /// `IngredientName`, or nil (no such cut, a language without spaces or name-first, or
    /// trailing text holding a digit: a figure is never ignored).
    static func split(_ line: String, words: LanguageWords) -> Split? {
        if !words.spaced || IngredientScaler.patterns(words).amountAfterName { return nil }
        for m in separator.findAll(line) {
            let core = trimEnd(line.u16Substring(0, m.start))
            if IngredientName.of(core, words: words) == nil { continue }
            let trailing = line.u16Substring(from: m.start).kTrimmed
            if trailing.utf16.count < 2 || trailing.unicodeScalars.contains(where: { CharacterSet.decimalDigits.contains($0) }) {
                return nil
            }
            return Split(core: core, trailing: trailing)
        }
        return nil
    }

    private static func trimEnd(_ s: String) -> String {
        var s = s
        while let last = s.last, last.isWhitespace { s.removeLast() }
        return s
    }

    /// The text the combiner reads `item` as: its core once its trailing text is a note or junk.
    static func effectiveText(_ item: GroceryItem, decisions: Decisions) -> String {
        guard let words = LanguageWords.forTag(item.language), let split = split(item.text, words: words) else { return item.text }
        return decisions.ignorableTrailing(split.trailing, language: words.language) ? split.core : item.text
    }

    /// `item`'s name as the combiner groups it, or nil.
    static func name(_ item: GroceryItem, decisions: Decisions) -> String? {
        LanguageWords.forTag(item.language).flatMap { IngredientName.of(effectiveText(item, decisions: decisions), words: $0) }
    }

    private static func aislesMeet(_ a: GroceryItem, _ b: GroceryItem) -> Bool {
        a.aisle == b.aisle || a.aisle == .other || b.aisle == .other
    }

    /// Close names on two lines in one language whose aisles could meet.
    static func samePairs(_ items: [GroceryItem], decisions: Decisions) -> [DecisionQuestion] {
        let named = items.compactMap { item in name(item, decisions: decisions).map { (item, $0) } }
        var out: [DecisionQuestion] = []
        for i in named.indices {
            for j in named.indices where j > i {
                let (a, na) = named[i], (b, nb) = named[j]
                guard a.language == b.language, na != nb, aislesMeet(a, b), let words = LanguageWords.forTag(a.language) else { continue }
                let q = DecisionQuestion.sameGrocery(na, nb, language: words.language)
                if DecisionCandidates.close(na, nb, words: words) && !out.contains(q) { out.append(q) }
            }
        }
        return out
    }

    /// Trailing text on a line whose core names what another line names, where they don't add up as written.
    static func trailingTexts(_ items: [GroceryItem], decisions: Decisions) -> [DecisionQuestion] {
        var out: [DecisionQuestion] = []
        for item in items {
            guard let words = LanguageWords.forTag(item.language), let split = split(item.text, words: words),
                  let core = IngredientName.of(split.core, words: words) else { continue }
            let partner = items.contains { other in
                other.id != item.id && other.language == item.language && aislesMeet(item, other)
                    && (IngredientName.of(other.text, words: words) == core || name(other, decisions: decisions) == core)
                    && GroceryCombiner.combine([item.text, other.text], words: words) == nil
            }
            let q = DecisionQuestion.trailingText(split.trailing, language: words.language)
            if partner && !out.contains(q) { out.append(q) }
        }
        return out
    }

    /// Where answers that just landed (`fresh`) file lines from Other (see the Kotlin).
    static func filing(_ items: [GroceryItem], fresh: Set<DecisionQuestion>, decisions: Decisions) -> [Aisle: [Int64]] {
        var moves: [(Int64, Aisle)] = []
        for item in items where item.aisle == .other {
            guard let words = LanguageWords.forTag(item.language) else { continue }
            if let split = split(item.text, words: words),
               fresh.contains(.trailingText(split.trailing, language: words.language)),
               decisions.ignorableTrailing(split.trailing, language: words.language) {
                let aisle = Aisles.of(split.core, words: words)
                if aisle != .other { moves.append((item.id, aisle)); continue }
            }
            guard let name = name(item, decisions: decisions) else { continue }
            let partner = items.first { other in
                guard other.aisle != .other, other.language == item.language,
                      let n = GroceryDecisions.name(other, decisions: decisions) else { return false }
                return fresh.contains(.sameGrocery(name, n, language: words.language))
                    && decisions.sameGrocery(name, n, language: words.language)
            }
            if let partner { moves.append((item.id, partner.aisle)) }
        }
        return Dictionary(grouping: moves, by: { $0.1 }).mapValues { $0.map(\.0) }
    }
}
