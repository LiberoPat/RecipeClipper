import Foundation

/// Ingredient amounts inside steps (#101): "Add the carrots" reads "Add ⟦2⟧ carrots", the amount
/// taken from the ingredient line as the reading view renders it (scaled, then converted), so it
/// follows the servings stepper and the unit menu. Deterministic and pure; no AI. A port of the
/// Kotlin `StepAmounts`, pinned to it by the differential corpus's `Step` rows; the rules are
/// documented there and in docs/decisions.md.
enum StepAmounts {

    /// A run of a step's text; `amount` marks one inserted from an ingredient line. A run with
    /// `lines` is a mention naming several ingredient lines with different amounts (#254): the
    /// screen shows it as a link to those lines (indexes into the lines given), never a number.
    struct Part: Equatable {
        let text: String
        var amount: Bool = false
        var lines: [Int] = []
        var plain: Bool { !amount && lines.isEmpty }
    }

    private final class Words {
        let articles: Set<String>
        let partWords: Set<String>
        let before: Set<String>
        let after: Set<String>
        let anyWordAfter: Bool
        let splitWords: Set<String>
        // "very cold unsalted butter" is still "the butter" (#254); with the names table's
        // modifiers, each word on its own.
        let descriptors: Set<String>
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
            let described = words.strings("steps", "lineDescriptors") + words.strings("names", "matchModifiers") +
                words.strings("names", "leadingWords")
            descriptors = Set(described.flatMap { $0.lowercased().split(separator: " ").map(String.init) })
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
    static func marked(_ parts: [Part]) -> String {
        parts.map { part in
            if part.amount { return "⟦\(part.text)⟧" }
            if !part.lines.isEmpty { return "⟪\(part.text)|\(part.lines.map(String.init).joined(separator: ","))⟫" }
            return part.text
        }.joined()
    }

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

    /// Words from `start` that name line `line`: `strict`ly by `IngredientName.matches`, or past
    /// its describing words; `exact` when they are the line's whole name.
    private struct Run { let line: Int; let start: Int; let strict: Bool; var exact = false }

    /// The longest run of words ending at `h` that names `s`'s ingredient, or nil.
    private static func run(_ step: String, _ tokens: [Token], _ h: Int, _ i: Int, _ s: Source, _ words: LanguageWords, _ w: Words) -> Run? {
        guard let head = s.head, let name = s.name, sameWord(tokens[h].bare, head, w) else { return nil }
        var best: Run?
        var start = h
        while start >= 0 && h - start < 6 {
            if start < h && !joined(step, tokens[start], tokens[start + 1]) { break }
            // A run may hold "de" ("farinha de trigo") but never starts on a word that says how
            // much or on the article: "a carrot" is "carrot" after "a".
            if !stops(tokens[start], w) {
                let phrase = step.u16Substring(tokens[start].start, tokens[h].start) + head
                if IngredientName.matches(phrase, name, words: words) {
                    let exact = IngredientName.key(IngredientDensities.headPhrase(phrase, words: words), words: words) ==
                        IngredientName.key(IngredientDensities.headPhrase(name, words: words), words: words)
                    best = Run(line: i, start: start, strict: true, exact: exact)
                } else if endsWith(name, phrase, words, { front in front.allSatisfy { w.descriptors.contains($0) } }) {
                    best = Run(line: i, start: start, strict: false)
                }
            }
            start -= 1
        }
        return best
    }

    /// True when `name` ends with `phrase`'s words and `front` accepts the words before them ("olive" oil).
    private static func endsWith(_ name: String, _ phrase: String, _ words: LanguageWords, _ front: ([String]) -> Bool) -> Bool {
        let n = IngredientName.key(IngredientDensities.headPhrase(name, words: words), words: words).components(separatedBy: " ")
        let p = IngredientName.key(IngredientDensities.headPhrase(phrase, words: words), words: words).components(separatedBy: " ")
        return p.count < n.count && Array(n.suffix(p.count)) == p && front(Array(n.dropLast(p.count)))
    }

    /// Replace step[start, end) with `amount`, then a space if `space`; the mention ends at `mentionEnd`.
    /// With `lines`, a link to those lines with the mention as its text, instead of an amount.
    private struct Insertion { let start: Int; let end: Int; let amount: String; let space: Bool; let mentionEnd: Int; var lines: [Int] = [] }

    private static func annotate(_ step: String, _ sources: [Source], _ words: LanguageWords, _ w: Words) -> [Part] {
        let tokens = tokens(step, w)
        var seen = Set<Int>()
        var insertions: [Insertion] = []
        // "Sauté onion and mushrooms - Turn the heat down…": a step's title names what the step
        // is about; its words are no mention, so the first mention is the one after it (#254).
        let title = titlePattern.find(step)?.end ?? 0
        for h in tokens.indices where tokens[h].start >= title {
            let runs = sources.indices.compactMap { run(step, tokens, h, $0, sources[$0], words, w) }
            guard let start = runs.map(\.start).min() else { continue }
            // The longest run names the mention: "brown sugar" is never "sugar". A line named
            // exactly by the step's words is the one: "the sugar" beside "brown sugar".
            let all = runs.filter { $0.start == start }
            let exact = all.filter(\.exact)
            let longest = exact.isEmpty ? all : exact
            var named = longest.map(\.line)
            // Named only past describing words ("the oil" for "olive oil"): any line ending in
            // the step's words could be the one ("sesame oil" too).
            if !longest.contains(where: \.strict) {
                let front = step.u16Substring(tokens[start].start, tokens[h].start)
                named += sources.indices.filter { i in
                    guard !named.contains(i), let head = sources[i].head, let name = sources[i].name else { return false }
                    return sameWord(tokens[h].bare, head, w) && endsWith(name, front + head, words, { _ in true })
                }
            }
            // "salt and pepper" has no name but uses the word: the mention could be either line.
            let nameless = sources.indices.filter { i in
                sources[i].name == nil && !sources[i].heading && sources[i].words.contains { sameWord($0, tokens[h].bare, w) }
            }
            // Only a line's first mention in the step, whether or not it gets an amount.
            if (named + nameless).allSatisfy({ seen.contains($0) }) { continue }
            seen.formUnion(named + nameless)
            // Lines that lend the same amount ("30 g butter" twice) are one answer.
            let amounts = named.map { sources[$0].amount }
            let amount: String? = amounts.allSatisfy({ $0 == amounts.first }) ? amounts.first ?? nil : nil
            if let amount, nameless.isEmpty {
                if let insertion = insertion(step, tokens, start, h, amount, w) { insertions.append(insertion) }
            } else if named.count + nameless.count > 1 {
                // Lines with different amounts, and the step doesn't say which (#254): no
                // number, but a link to the lines, where the same rules would place an amount.
                if insertion(step, tokens, start, h, "", w) == nil { continue }
                let from = tokens[start].start
                let to = tokens[h].end
                insertions.append(Insertion(start: from, end: to, amount: step.u16Substring(from, to), space: false,
                                            mentionEnd: to, lines: (named + nameless).sorted()))
            }
        }
        return parts(step, insertions)
    }

    // A few words, no sentence end, then a spaced dash.
    private static let titlePattern = JRegex(#"^[^.!?:;\n]{1,60}?\s[-–—]\s"#)
    private static let dashes: Set<String> = ["-", "–", "—"]

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

    // The token starts its step or sentence, or follows a spaced dash ("Butter - Melt butter"),
    // so it is taken as the verb ("Melt butter").
    private static func clauseFirst(_ step: String, _ tokens: [Token], _ i: Int) -> Bool {
        if i == 0 { return true }
        let gap = step.u16Substring(tokens[i - 1].end, tokens[i].start)
        if gap.contains(where: { clauseEnds.contains($0) }) { return true }
        guard gap.u16Count >= 3, let first = gap.first, let last = gap.last else { return false }
        return first.isWhitespace && last.isWhitespace && dashes.contains(gap.kTrimmed)
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
            out.append(ins.lines.isEmpty ? Part(text: ins.amount, amount: true) : Part(text: ins.amount, lines: ins.lines))
            cursor = ins.end
            if ins.space { out.append(Part(text: " ")) }
            covered = ins.mentionEnd
        }
        if cursor < step.u16Count { out.append(Part(text: step.u16Substring(from: cursor))) }
        // Adjacent plain runs as one.
        var merged: [Part] = []
        for part in out {
            if let last = merged.last, last.plain, part.plain {
                merged[merged.count - 1] = Part(text: last.text + part.text)
            } else {
                merged.append(part)
            }
        }
        return merged
    }
}

