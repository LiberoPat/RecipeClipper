import Foundation

/// Ingredient amounts inside steps (#101): "Add the carrots" reads "Add ⟦2⟧ carrots", the amount
/// taken from the ingredient line as the reading view renders it (scaled, then converted), so it
/// follows the servings stepper and the unit menu. Deterministic and pure; no AI. A port of the
/// Kotlin `StepAmounts`, pinned to it by the differential corpus's `Step` rows; the rules are
/// documented there and in docs/decisions.md.
enum StepAmounts {

    /// A run of a step's text; `amount` marks one inserted from an ingredient line.
    struct Part: Equatable {
        let text: String
        var amount: Bool = false
    }

    private final class Words {
        let articles: Set<String>
        let partWords: Set<String>
        let before: Set<String>
        let after: Set<String>
        let anyWordAfter: Bool
        let splitWords: Set<String>
        let plurals: [(String, String)]
        // "l'huile": an article or part word elided onto the next word.
        let elisions: [String]
        // Words that may sit between an article and the mention ("the melted butter").
        let modifiers: Set<String>

        init(_ words: LanguageWords) {
            func set(_ key: String) -> Set<String> { Set(words.strings("steps", key).map { $0.lowercased() }) }
            articles = set("articles")
            partWords = set("partWords")
            before = set("before")
            after = set("after")
            anyWordAfter = words.table("steps")["anyWordAfter"] as? Bool ?? false
            splitWords = set("splitWords")
            plurals = (words.table("steps")["plurals"] as? [[String]] ?? []).map { ($0[0], $0[1]) }
            elisions = articles.union(partWords).filter { $0.hasSuffix("'") }
                .sorted { $0.u16Count != $1.u16Count ? $0.u16Count > $1.u16Count : $0 < $1 }
            let all = words.strings("names", "matchModifiers") + words.strings("names", "leadingWords") +
                words.strings("names", "trailingWords") + Array(IngredientDensities.trailingModifiers(words))
            modifiers = Set(all.flatMap { $0.lowercased().split(separator: " ").map(String.init) })
        }
    }

    private static let token = JRegex(#"[\p{L}\p{M}\p{N}]+(?:['’][\p{L}\p{M}\p{N}]+)*"#)
    private static let number = JRegex(#"\p{N}"#)

    /// A word in a step or line: its UTF-16 range, lowercased with ’ as ', and without apostrophes.
    private struct Token {
        let start: Int; let end: Int; let lower: String
        var bare: String { lower.replacingOccurrences(of: "'", with: "") }
        var isNumber: Bool { StepAmounts.number.containsMatch(in: lower) }
    }

    /// One ingredient line: its name, its amount as rendered (nil: never inserted).
    private struct Source {
        let name: String?; let head: String?; let amount: String?; let words: [String]; let heading: Bool
    }

    /// `steps` as parts, with amounts from `lines` (the ingredient lines as currently rendered)
    /// inserted where the rules allow. `words` are the recipe's language's; nil, or a language
    /// without spaces, leaves every step as written.
    static func annotate(_ steps: [String], lines: [String], words: LanguageWords?) -> [[Part]] {
        guard let words, words.spaced else { return steps.map { [Part(text: $0)] } }
        let w = words.compiled(Words.self, Words.init)
        let sources = lines.map { source($0, words, w) }
        return steps.map { annotate($0, sources, words, w) }
    }

    /// `parts` as one string, each amount in ⟦ ⟧: how the differential corpus pins them.
    static func marked(_ parts: [Part]) -> String { parts.map { $0.amount ? "⟦\($0.text)⟧" : $0.text }.joined() }

    private static func tokens(_ text: String, _ w: Words) -> [Token] {
        var out: [Token] = []
        for m in token.findAll(text) {
            let lower = m.value.lowercased().replacingOccurrences(of: "’", with: "'")
            if let elision = w.elisions.first(where: { lower.hasPrefix($0) && lower.u16Count > $0.u16Count }) {
                out.append(Token(start: m.start, end: m.start + elision.u16Count, lower: elision))
                out.append(Token(start: m.start + elision.u16Count, end: m.end, lower: lower.u16Substring(from: elision.u16Count)))
            } else {
                out.append(Token(start: m.start, end: m.end, lower: lower))
            }
        }
        return out
    }

    private static func source(_ line: String, _ words: LanguageWords, _ w: Words) -> Source {
        let tokens = tokens(line, w)
        let bare = tokens.map(\.bare)
        let heading = line.kTrimmed.hasSuffix(":")
        guard let name = IngredientName.of(line, words: words) else {
            return Source(name: nil, head: nil, amount: nil, words: bare, heading: heading)
        }
        let head = name.components(separatedBy: " ").last ?? name
        return Source(name: name, head: head, amount: amount(line, name, tokens, words, w), words: bare, heading: heading)
    }

    // The rendered line's text before its name ("250 g", "2 large", "200 g de"), or nil.
    private static func amount(_ line: String, _ name: String, _ tokens: [Token], _ words: LanguageWords, _ w: Words) -> String? {
        // Only a line the scaler reads, so a line that stays as written never lends a number.
        if IngredientScaler.scale(line, factor: 2.0, words: words) == line { return nil }
        guard let lead = IngredientScaler.patterns(words).leading.find(line) else { return nil }
        let size = name.components(separatedBy: " ").count
        for j in tokens.indices where tokens[j].start >= lead.end {
            for k in j..<min(tokens.count, j + size + 2) {
                if IngredientDensities.headPhrase(line.u16Substring(tokens[j].start, tokens[k].end), words: words) != name { continue }
                let amount = line.u16Substring(0, tokens[j].start).kTrimmed
                if amount.isEmpty || amount.contains(",") || amount.contains(";") { return nil }
                // "2 cups flour, divided", "1 tsp salt, plus more to taste": used in parts.
                if tokens[(k + 1)...].contains(where: { w.splitWords.contains($0.bare) }) { return nil }
                return amount
            }
        }
        return nil
    }

    /// True when `a` and `b` are one word, singular or plural ("carrot", "carrots").
    private static func sameWord(_ a: String, _ b: String, _ w: Words) -> Bool {
        a == b || w.plurals.contains { one, many in plural(a, one, many) == b || plural(b, one, many) == a }
    }

    private static func plural(_ word: String, _ one: String, _ many: String) -> String? {
        word.hasSuffix(one) ? word.u16Substring(0, word.u16Count - one.u16Count) + many : nil
    }

    // Words that end a run leftwards: they say how much, or are the article.
    private static func stops(_ t: Token, _ w: Words) -> Bool {
        t.isNumber || w.partWords.contains(t.lower) || w.articles.contains(t.lower)
    }

    // Only spaces, or a hyphen ("all-purpose"), between two words of one name.
    private static func joined(_ step: String, _ a: Token, _ b: Token) -> Bool {
        let gap = step.u16Substring(a.end, b.start)
        return gap.kIsBlank || gap == "-"
    }

    /// The earliest token where a run of words ending at `h` names `s`'s ingredient, or -1.
    private static func runStart(_ step: String, _ tokens: [Token], _ h: Int, _ s: Source, _ words: LanguageWords, _ w: Words) -> Int {
        guard let head = s.head, let name = s.name, sameWord(tokens[h].bare, head, w) else { return -1 }
        var best = -1
        var start = h
        while start >= 0 && h - start < 6 {
            if start < h && !joined(step, tokens[start], tokens[start + 1]) { break }
            // A run may hold "de" ("farinha de trigo") but never starts on a word that says how
            // much or on the article: "a carrot" is "carrot" after "a".
            let phrase = step.u16Substring(tokens[start].start, tokens[h].start) + head
            if !stops(tokens[start], w) && IngredientName.matches(phrase, name, words: words) { best = start }
            start -= 1
        }
        return best
    }

    /// Replace step[start, end) with `amount`, then a space if `space`; the mention ends at `mentionEnd`.
    private struct Insertion { let start: Int; let end: Int; let amount: String; let space: Bool; let mentionEnd: Int }

    private static func annotate(_ step: String, _ sources: [Source], _ words: LanguageWords, _ w: Words) -> [Part] {
        let tokens = tokens(step, w)
        var seen = Set<Int>()
        var insertions: [Insertion] = []
        for h in tokens.indices {
            let runs: [(Int, Int)] = sources.indices.compactMap { i in
                let start = runStart(step, tokens, h, sources[i], words, w)
                return start >= 0 ? (i, start) : nil
            }
            guard let start = runs.map(\.1).min() else { continue }
            // The longest run names the mention: "brown sugar" is never "sugar".
            let named = runs.filter { $0.1 == start }.map(\.0)
            // Only a line's first mention in the step, whether or not it gets an amount.
            if named.allSatisfy({ seen.contains($0) }) { continue }
            seen.formUnion(named)
            if named.count != 1 { continue }
            // "salt and pepper" has no name but uses the word: the mention could be either line.
            if sources.contains(where: { $0.name == nil && !$0.heading && $0.words.contains { sameWord($0, tokens[h].bare, w) } }) { continue }
            guard let amount = sources[named[0]].amount else { continue }
            if let insertion = insertion(step, tokens, start, h, amount, w) { insertions.append(insertion) }
        }
        return parts(step, insertions)
    }

    private static let ends: Set<Character> = [",", ".", ";", ":", "!", "?", ")", "&", "–", "—"]
    private static let listOrClause: Set<Character> = [",", ";", ":", "(", ".", "!", "?", "&"]
    private static let clauseEnds: Set<Character> = [".", "!", "?", ";", ":"]

    // The mention ends the name: the step ends, or punctuation or an allowed word follows.
    private static func endsName(_ step: String, _ tokens: [Token], _ h: Int, _ w: Words) -> Bool {
        let next = h + 1 < tokens.count ? tokens[h + 1] : nil
        let gap = step.u16Substring(tokens[h].end, next?.start ?? step.u16Count).kTrimmed
        if let first = gap.first { return ends.contains(first) }
        guard let next else { return true }
        return w.anyWordAfter || w.after.contains(next.lower)
    }

    // The token starts its step or sentence, so it is taken as the verb ("Melt butter").
    private static func clauseFirst(_ step: String, _ tokens: [Token], _ i: Int) -> Bool {
        i == 0 || step.u16Substring(tokens[i - 1].end, tokens[i].start).contains { clauseEnds.contains($0) }
    }

    private static func insertion(_ step: String, _ tokens: [Token], _ start: Int, _ h: Int, _ amount: String, _ w: Words) -> Insertion? {
        if !endsName(step, tokens, h, w) { return nil }
        // "the melted butter": the amount goes before the words that describe it.
        var m = start
        while m > 0 && joined(step, tokens[m - 1], tokens[m]) && !stops(tokens[m - 1], w) && w.modifiers.contains(tokens[m - 1].bare) { m -= 1 }
        let apostrophe = amount.hasSuffix("'") || amount.hasSuffix("’")
        let at = tokens[m].start
        let insert = Insertion(start: at, end: at, amount: amount, space: !apostrophe, mentionEnd: tokens[h].end)
        if m == 0 { return insert }
        let p = tokens[m - 1]
        let gap = step.u16Substring(p.end, at)
        // After a comma ("the carrots, celery") or a sentence's end.
        if !gap.kIsBlank { return gap.contains { listOrClause.contains($0) } ? insert : nil }
        if w.articles.contains(p.lower) {
            if m >= 2 {
                let q = tokens[m - 2]
                if step.u16Substring(q.end, p.start).kIsBlank && (q.isNumber || w.partWords.contains(q.lower)) { return nil }
            }
            // The amount takes the article's place: "the carrots" → "2 carrots", "l'huile" → "… huile".
            let elided = gap.isEmpty
            return Insertion(start: p.start, end: apostrophe ? at : p.end, amount: amount, space: elided && !apostrophe, mentionEnd: tokens[h].end)
        }
        if p.isNumber || w.partWords.contains(p.lower) { return nil }
        return w.before.contains(p.lower) || clauseFirst(step, tokens, m - 1) ? insert : nil
    }

    private static func parts(_ step: String, _ insertions: [Insertion]) -> [Part] {
        var out: [Part] = []
        var cursor = 0
        var covered = 0
        let ordered = insertions.enumerated().sorted { $0.element.start != $1.element.start ? $0.element.start < $1.element.start : $0.offset < $1.offset }
        for ins in ordered.map(\.element) {
            if ins.start < covered { continue }
            if ins.start > cursor { out.append(Part(text: step.u16Substring(cursor, ins.start))) }
            out.append(Part(text: ins.amount, amount: true))
            cursor = ins.end
            if ins.space { out.append(Part(text: " ")) }
            covered = ins.mentionEnd
        }
        if cursor < step.u16Count { out.append(Part(text: step.u16Substring(from: cursor))) }
        // Adjacent plain runs as one.
        var merged: [Part] = []
        for part in out {
            if let last = merged.last, !last.amount, !part.amount {
                merged[merged.count - 1] = Part(text: last.text + part.text)
            } else {
                merged.append(part)
            }
        }
        return merged
    }
}

