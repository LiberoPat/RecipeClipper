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

    /// The name question for a line with no separator whose name has words the aisle table
    /// doesn't match after ones it does ("2 onions dfsafs"), or nil (see the Kotlin).
    static func nameQuestion(_ line: String, words: LanguageWords) -> DecisionQuestion? {
        if !words.spaced || IngredientScaler.patterns(words).amountAfterName || split(line, words: words) != nil { return nil }
        guard let name = IngredientName.of(line, words: words) else { return nil }
        let parts = name.components(separatedBy: " ")
        guard parts.count >= 2, Aisles.ofName(name, words: words) == .other else { return nil }
        let known = (1..<parts.count).contains { Aisles.ofName(parts.prefix($0).joined(separator: " "), words: words) != .other }
        return known ? .ingredientName(line, language: words.language) : nil
    }

    /// The line cut after the model's `name` for it, or nil: the name must be in the line as whole
    /// words (`PageRecipeCheck.find`), the line up to it must read as an ingredient line whose
    /// name ends with it, and what is left must be two characters or more with no digit.
    static func nameSplit(_ line: String, name: String, words: LanguageWords) -> Split? {
        if !words.spaced || IngredientScaler.patterns(words).amountAfterName { return nil }
        guard let found = PageRecipeCheck.find(line, name, kind: .name) else { return nil }
        let ns = line as NSString, u = Array(line.utf16), length = (found as NSString).length
        var at = ns.range(of: found, options: .literal).location
        while at != NSNotFound {
            let end = at + length
            let clean = (at == 0 || !PageRecipeCheck.isLetterOrDigit(u[at - 1]))
                && (end == u.count || !PageRecipeCheck.isLetterOrDigit(u[end]))
            if clean {
                let core = trimEnd(line.u16Substring(0, end))
                let trailing = line.u16Substring(from: end).kTrimmed
                guard let coreName = IngredientName.of(core, words: words),
                      coreName.hasSuffix(DecisionQuestion.normalize(found)) else { return nil }
                if trailing.utf16.count < 2 || trailing.unicodeScalars.contains(where: { CharacterSet.decimalDigits.contains($0) }) {
                    return nil
                }
                return Split(core: core, trailing: trailing)
            }
            at = ns.range(of: found, options: .literal, range: NSRange(location: at + 1, length: u.count - at - 1)).location
        }
        return nil
    }

    /// `split`, else, for a line with no separator, the cut after the model's name for it.
    static func split(_ line: String, words: LanguageWords, decisions: Decisions) -> Split? {
        if let split = split(line, words: words) { return split }
        return decisions.ingredientName(line, language: words.language).flatMap { nameSplit(line, name: $0, words: words) }
    }

    /// The text the combiner reads `item` as: its core once its trailing text is a note or junk.
    static func effectiveText(_ item: GroceryItem, decisions: Decisions) -> String {
        guard let words = LanguageWords.forTag(item.language),
              let split = split(item.text, words: words, decisions: decisions) else { return item.text }
        return decisions.ignorableTrailing(split.trailing, language: words.language) ? split.core : item.text
    }

    /// The text Groceries shows for `item`: its core once its trailing text is definitely junk,
    /// else as written (a note still shows). Display only: the stored line is never rewritten.
    static func shownText(_ item: GroceryItem, decisions: Decisions) -> String {
        guard let words = LanguageWords.forTag(item.language),
              let split = split(item.text, words: words, decisions: decisions) else { return item.text }
        return decisions.junkTrailing(split.trailing, language: words.language) ? split.core : item.text
    }

    /// The name questions worth asking (`nameQuestion`) for `items`.
    static func ingredientNames(_ items: [GroceryItem]) -> [DecisionQuestion] {
        var out: [DecisionQuestion] = []
        for item in items {
            if let words = LanguageWords.forTag(item.language), let q = nameQuestion(item.text, words: words), !out.contains(q) {
                out.append(q)
            }
        }
        return out
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

    /// The trailing-text questions for every line with trailing text (the owner's option 2): cut
    /// at a separator (`split`) or after the model's name for it (`nameSplit`), once per text.
    static func trailingTexts(_ items: [GroceryItem], decisions: Decisions) -> [DecisionQuestion] {
        var out: [DecisionQuestion] = []
        for item in items {
            guard let words = LanguageWords.forTag(item.language),
                  let split = split(item.text, words: words, decisions: decisions) else { continue }
            let q = DecisionQuestion.trailingText(split.trailing, language: words.language)
            if !out.contains(q) { out.append(q) }
        }
        return out
    }

    /// Where answers that just landed (`fresh`) file lines from Other (see the Kotlin).
    static func filing(_ items: [GroceryItem], fresh: Set<DecisionQuestion>, decisions: Decisions) -> [Aisle: [Int64]] {
        var moves: [(Int64, Aisle)] = []
        for item in items where item.aisle == .other {
            guard let words = LanguageWords.forTag(item.language) else { continue }
            if let split = split(item.text, words: words, decisions: decisions),
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
