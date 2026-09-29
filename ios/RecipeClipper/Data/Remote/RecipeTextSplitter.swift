import Foundation

/// What `RecipeTextSplitter` found in a block of text. The name comes from elsewhere (a
/// Reddit post's title), so it isn't here. `language` is the language the text was read in when
/// its words said so (#208); nil when nothing was clear and English was used.
struct SplitRecipe: Equatable {
    let ingredients: [String]
    let instructions: [String]
    let yield: String?
    let prepTime: String?
    let cookTime: String?
    let totalTime: String?
    var language: String? = nil
}

/// Turns free text laid out as a recipe (a Reddit post body, or a comment with the recipe or a
/// transcription of a recipe card) into ingredients and steps. Ported line for line from
/// Android's `RecipeTextSplitter`; its doc comment has the full rules.
///
/// **It never guesses.** Text splits only into an ingredients block and steps, each with lines:
///  - ingredients under a header ("Ingredients", "**Dry ingredients**", "Ingredeints:"), or,
///    with none, the amount lines just above the steps (two amounts at least, every line
///    reading like an ingredient);
///  - steps under a header ("Directions", "**Cooking steps:**"), or a numbered list from 1 or
///    "Step 1" with no header over it.
/// Notes, "Edit:" and the story before the first section are dropped, except a labelled yield
/// or time. A bold line inside a section is a group's name ("Sauce:"). A line with a bare link
/// is left out.
///
/// Every word is the text's own language's (#208): `shared/tables/<language>/splitter.json`,
/// English by default; `detectAndSplit` picks the language from the text's words, as a page's is
/// detected (#14), else English. Languages are never merged.
///
/// Pure: text in, data out.
enum RecipeTextSplitter {

    enum Section { case ingredients, instructions, end }

    /// One line of Markdown as plain text, with what its markup said about it (see Android's
    /// `RecipeTextSplitter.Line`). A bare "Step 3" has empty `text`.
    struct Line {
        let text: String
        var strong = false
        var italic = false
        var listed = false
        var number: Int?
        var step = false

        /// Set apart the way a header is: bold, a heading, italics, a trailing colon or capitals.
        var headerLike: Bool { strong || italic || text.hasSuffix(":") || RecipeTextSplitter.isCapitals(text) }
    }

    /// A header: the section it opens, and the group name ("Dry ingredients:") it adds, if any.
    struct Header {
        let section: Section
        var label: String?
    }

    /// One language's splitter words (`splitter.json`), built once per language.
    private final class Words {
        let ingredientHeaders: Set<String>
        let instructionHeaders: Set<String>
        let endHeaders: Set<String>
        /// The word that ends a header set apart as one ("**Dry ingredients**", "COOKING STEPS"),
        /// or, where `keywordFirst`, starts it ("**Ingredientes secos**").
        let keywords: [String: Section]
        let ingredientKeywords: Set<String>
        let keywordFirst: Bool
        /// Words beside the keyword that name no group ("Cooking steps", "The ingredients").
        let plainWords: Set<String>
        /// A header whose other words hold one of these is a step or a sentence.
        let verbs: Set<String>
        /// One-word headers a typo away from the real word ("Ingredeints", "Intructions").
        let misspelt: [(String, Section)]
        /// "Ingredients for the cake:" is a header only with its colon.
        let forHeader: JRegex
        /// "Ingredients for 4 servings", "Zutaten für 4 Personen": a header without a colon.
        let servesHeader: JRegex
        let editLine: JRegex
        let stepLabel: JRegex
        let yieldLine: JRegex
        let yieldWhole: JRegex
        /// "For 4 people", "Pour 6 personnes": kept whole, as "Serves 4" is.
        let yieldFor: JRegex
        /// "Prep time: 10 min": group 1 prep, 2 cook, 3 total, 4 the time.
        let time: JRegex
        let amount: JRegex
        /// A unit word run into the next, taken whole (see Android's `Words.glued`).
        let glued: JRegex

        init(_ words: LanguageWords) {
            let table = words.table("splitter")
            func list(_ key: String) -> [String] { SharedTables.strings(table, key) }
            func list(_ obj: String, _ key: String) -> [String] {
                SharedTables.strings(table[obj] as? SharedTables.Table ?? [:], key)
            }
            func alt(_ fragments: [String]) -> String { SharedTables.alternation(fragments) }

            ingredientHeaders = Set(list("ingredientHeaders"))
            instructionHeaders = Set(list("instructionHeaders"))
            endHeaders = Set(list("endHeaders"))
            var keywords: [String: Section] = [:]
            for (key, section) in [("ingredients", Section.ingredients), ("instructions", .instructions), ("end", .end)] {
                for word in list("keywords", key) where keywords[word] == nil { keywords[word] = section }
            }
            self.keywords = keywords
            ingredientKeywords = Set(list("keywords", "ingredients"))
            keywordFirst = table["keywordFirst"] as? Bool ?? false
            plainWords = Set(list("plainWords")).union(keywords.keys)
            verbs = Set(list("verbs"))
            misspelt = list("misspelt", "ingredients").map { ($0, Section.ingredients) }
                + list("misspelt", "instructions").map { ($0, Section.instructions) }

            let servingWords = SharedTables.strings(words.table("yield"), "serving")
            let approx = alt(list("approx"))
            let count = #"(?:"# + approx + #")?\d+(?:\s*[-–]\s*\d+)?"#
            forHeader = JRegex(
                "^(?:(" + alt(list("forHeader", "ingredients")) + ")|" + alt(list("forHeader", "instructions")) + ")"
                    + #"\s+"# + alt(list("forHeader", "joiners")) + #"\s+.+:$"#
            )
            servesHeader = JRegex(
                "^" + alt(list("forHeader", "ingredients")) + #"\s+"# + alt(list("forHeader", "joiners")) + #"\s+"#
                    + count + #"\s+"# + alt(servingWords) + "$",
                ignoreCase: true
            )
            editLine = JRegex("^" + alt(list("editWords")) + #"\b[^:]{0,12}:"#, ignoreCase: true)
            stepLabel = JRegex("^" + alt(list("stepLabels")) + #"\s*(\d{1,2})\s*(?:[:.)\-–—]\s*|$)"#, ignoreCase: true)
            yieldLine = JRegex(
                "^(" + alt(list("yield", "whole") + list("yield", "labels")) + #")\b\s*:?\s*(\S.*)$"#, ignoreCase: true
            )
            yieldWhole = JRegex("^" + alt(list("yield", "whole")) + "$", ignoreCase: true)
            yieldFor = JRegex(
                "^" + alt(list("yield", "for")) + #"\s+"# + count + #"\s+"# + alt(servingWords) + #"\.?$"#, ignoreCase: true
            )
            time = JRegex(
                "^(?:(" + alt(list("times", "prep")) + ")|(" + alt(list("times", "cook")) + ")|("
                    + alt(list("times", "total")) + #"))\s*:\s*(\S.*)$"#,
                ignoreCase: true
            )
            amount = JRegex(
                "^(?:" + approx + #"|~\s*)?[0-9"# + RecipeTextSplitter.fractions + #"]|[0-9"# + RecipeTextSplitter.fractions
                    + #"]\s*"# + alt(list("amountUnits")) + #"\.?(?![A-Za-z])"#
                    + list("amountPatterns").map { "|" + $0 }.joined(),
                ignoreCase: true
            )
            glued = JRegex(
                #"^\s*(?!"# + alt(list("gluedExceptions")) + ")(?>" + alt(list("gluedUnits")) + #")\.?\p{L}"#,
                ignoreCase: true
            )
        }
    }

    private static func words(_ words: LanguageWords) -> Words { words.compiled(Words.self, Words.init) }

    /// The language `text`'s words clearly say (#14's detection), or nil.
    static func languageOf(_ text: String) -> String? { LanguageWords.detect(text) }

    /// The words to read text in `language` with: its own, else English.
    static func wordsFor(_ language: String?) -> LanguageWords { LanguageWords.forTag(language) ?? .english }

    /// `split` in the language `text`'s words say (with `context`, such as the post's title, read
    /// for detection too), else English (#208). The result carries that language.
    static func detectAndSplit(_ text: String, context: String = "") -> SplitRecipe? {
        let language = languageOf(context.isEmpty ? text : context + "\n" + text)
        guard var split = split(text, words: wordsFor(language)) else { return nil }
        split.language = language
        return split
    }

    /// Whether `rest`, a line after its amount, starts with one of `words`' units run into the
    /// next word ("cupraisins"), for `PhotoTextSorter.suspect`.
    static func gluedUnit(_ rest: String, words: LanguageWords) -> Bool { self.words(words).glued.containsMatch(in: rest) }

    /// "(serves 4)", "<for 3~4 people>", "[metric]", "（2人分）" at the end of a header.
    private static let trailingParenthetical = JRegex(#"\s*[(<\[（【][^)>\]）】]*[)>\]）】]$"#)
    private static let wordBreak = JRegex(#"[\s/]+"#)

    /// The header `line` is, or nil for an ordinary line. A numbered item or a step never is.
    static func header(_ line: Line, words: LanguageWords = .english) -> Header? {
        if line.number != nil || line.step { return nil }
        let w = self.words(words)
        let text = line.text
        if w.editLine.containsMatch(in: text) { return Header(section: .end) }
        let lower = text.lowercased().kTrimmed.replacingOccurrences(of: "’", with: "'")
        if let m = w.forHeader.matchEntire(lower) {
            return Header(section: m[1].isEmpty ? .instructions : .ingredients)
        }
        let base = bare(lower)
        if w.ingredientHeaders.contains(base) { return Header(section: .ingredients) }
        if w.instructionHeaders.contains(base) { return Header(section: .instructions) }
        if w.endHeaders.contains(base) { return Header(section: .end) }
        if w.servesHeader.matchEntire(base) != nil { return Header(section: .ingredients) }
        if !line.headerLike { return nil }
        let parts = wordBreak.split(base).filter { !$0.isEmpty }
        if (2...4).contains(parts.count) {
            if let header = keywordHeader(text, w.keywords[parts[parts.count - 1]], Array(parts.dropLast()), w) {
                return header
            }
            if w.keywordFirst, let header = keywordHeader(text, w.keywords[parts[0]], Array(parts.dropFirst()), w) {
                return header
            }
            // "Ingredients needed", "Ingredient amounts"
            if w.ingredientKeywords.contains(parts[0]), parts.dropFirst().allSatisfy(w.plainWords.contains) {
                return Header(section: .ingredients)
            }
        }
        if parts.count == 1, base.u16Count >= 8,
           let match = w.misspelt.first(where: { editDistance(base, $0.0) <= 2 }) {
            return Header(section: match.1)
        }
        return nil
    }

    /// A header of a keyword and the words beside it (`modifiers`), which name a group unless they
    /// are all plain words; nil when there's no keyword, or a verb makes it a step.
    private static func keywordHeader(_ text: String, _ section: Section?, _ modifiers: [String], _ w: Words) -> Header? {
        guard let section, !modifiers.contains(where: w.verbs.contains),
              modifiers.allSatisfy({ $0.allSatisfy { $0.isLetter || "'&+-–".contains($0) } })
        else { return nil }
        let plain = section == .end || modifiers.allSatisfy(w.plainWords.contains)
        return Header(section: section, label: plain ? nil : trimEnd(text, [":", " "]) + ":")
    }

    /// The section a line of Markdown opens, or nil for an ordinary line.
    static func section(_ raw: String, words: LanguageWords = .english) -> Section? {
        header(line(raw, words: words), words: words)?.section
    }

    /// Kotlin's `isLetterOrDigit`, near enough for headers: a fraction such as "½" is neither.
    private static func isLetterOrDigit(_ c: Character) -> Bool { c.isLetter || c.isWholeNumber }

    /// A lowercased header without what may surround it: a leading emoji or symbol, a trailing
    /// colon, dash, emoji or parenthetical ("ingredients (serves 4)").
    private static func bare(_ lower: String) -> String {
        var s = String(lower.drop { !isLetterOrDigit($0) })
        s = trimEnd(s, [":", " ", "-", "–", "—"])
        s = trailingParenthetical.replace(s, with: "")
        while let last = s.last, !isLetterOrDigit(last) { s.removeLast() }
        return s
    }

    /// Capitals: three letters or more, none lowercase and some uppercase (a script without case,
    /// such as Japanese, is never in capitals).
    fileprivate static func isCapitals(_ text: String) -> Bool {
        let letters = text.filter(\.isLetter)
        return letters.count >= 3 && !letters.contains(where: \.isLowercase) && letters.contains(where: \.isUppercase)
    }

    /// Levenshtein distance over UTF-16 units, as Kotlin counts them.
    static func editDistance(_ a: String, _ b: String) -> Int {
        let a = Array(a.utf16), b = Array(b.utf16)
        guard !a.isEmpty, !b.isEmpty else { return max(a.count, b.count) }
        var previous = Array(0...b.count)
        for i in 1...a.count {
            var current = [Int](repeating: 0, count: b.count + 1)
            current[0] = i
            for j in 1...b.count {
                let cost = a[i - 1] == b[j - 1] ? 0 : 1
                current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.count]
    }

    /// Kotlin's `trimEnd(vararg chars)`.
    private static func trimEnd(_ s: String, _ chars: Set<Character>) -> String {
        var out = s
        while let last = out.last, chars.contains(last) { out.removeLast() }
        return out
    }

    private static let blockquote = JRegex(#"^(?:>\s*)+"#)
    private static let heading = JRegex(#"^#{1,6}(?!\d)\s*"#)
    private static let rule = JRegex(#"^(?:[-*_=]\s*){3,}$"#)
    /// "-", "*", "+" and dashes need a space after them; a bullet glyph doesn't.
    private static let bullet = JRegex(#"^(?:[-*+–—]\s+|[•·・▪◦▢□☐○●►✓✔]\s*)"#)
    private static let numbered = JRegex(#"^\(?(\d{1,2})[.)]\s+"#)
    private static let checkbox = JRegex(#"^\[[ xX]?]\s+"#)
    private static let link = JRegex(#"\[([^\]]+)]\((?:[^()]|\([^)]*\))*\)"#)
    private static let emphasis = JRegex(#"\*\*|__|~~"#)
    /// Italics inside a line ("Glaze *(for grilling)*"); a lone "*" (a footnote) stays.
    private static let italicRun = JRegex(#"(?<![A-Za-z0-9_*])\*(?![\s*])([^*]+?)(?<!\s)\*(?![A-Za-z0-9_*])"#)
    private static let escape = JRegex(#"\\([\\`*_{}\[\]()#+\-.!>~^|])"#)

    /// One line of Markdown as plain text: `stripHtml` first (which also trims and collapses
    /// whitespace), backslash escapes undone, then the quote, heading, rule, list marker, link
    /// and emphasis syntax removed. "1.5 cups" keeps its number: a list marker needs a space.
    static func line(_ raw: String, words: LanguageWords = .english) -> Line {
        var s = JsonLdRecipeParser.stripHtml(raw)
        s = trimEnd(escape.replace(s) { $0[1] }, ["\\"]).kTrimmed
        s = blockquote.replace(s, with: "")
        let isHeading = heading.containsMatch(in: s)
        s = heading.replace(s, with: "")
        if rule.matchEntire(s) != nil { return Line(text: "") }
        var listed = false
        var number: Int?
        if let m = bullet.find(s) { s = s.u16Substring(from: m.end); listed = true }
        if let m = checkbox.find(s) { s = s.u16Substring(from: m.end); listed = true }
        if let m = numbered.find(s) { s = s.u16Substring(from: m.end); listed = true; number = Int(m[1]) }

        // "**Ingredients**", "**Ingredient amounts**:", "*optional*": wholly bold or italic.
        let core = trimEnd(s, [":"]).kTrimmed
        let bold = core.u16Count > 4 && (core.hasPrefix("**") && core.hasSuffix("**") || core.hasPrefix("__") && core.hasSuffix("__"))
        s = link.replace(s) { $0[1] }
        s = emphasis.replace(s, with: "").kTrimmed
        var italic = false
        if !bold, s.u16Count > 2, let first = s.first, first == "*" || first == "_", s.last == first {
            s = String(s.dropFirst().dropLast()).kTrimmed
            italic = true
        }
        s = italicRun.replace(s) { $0[1] }
        var step = false
        if let m = self.words(words).stepLabel.find(s) { s = s.u16Substring(from: m.end).kTrimmed; step = true; number = Int(m[1]) }
        return Line(text: s, strong: isHeading || bold, italic: italic, listed: listed, number: number, step: step)
    }

    /// `line`'s text alone, in English.
    static func cleanLine(_ raw: String) -> String { line(raw).text }

    /// Every line of `text`, cleaned, blank ones included.
    static func lines(_ text: String, words: LanguageWords = .english) -> [Line] {
        text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n").map { line($0, words: words) }
    }

    /// Fractions a recipe writes as one character.
    fileprivate static let fractions = "½⅓⅔¼¾⅛⅜⅝⅞"

    static func isAmount(_ text: String, words: LanguageWords = .english) -> Bool {
        self.words(words).amount.containsMatch(in: text)
    }

    /// The longest line that reads like an ingredient without an amount or list marker.
    private static let shortLine = 40

    /// What ends a sentence, so a line ending in one doesn't read like an ingredient.
    private static let sentenceEnd = ".!?。！？"

    /// An ingredients block with no header needs at least this many amounts.
    static let minAmounts = 2

    /// A line with a bare address in it is a pointer elsewhere, never an ingredient or a step.
    private static func isPointer(_ text: String) -> Bool { text.contains("http://") || text.contains("https://") }

    private static func readsLikeIngredient(_ line: Line, _ words: LanguageWords) -> Bool {
        isAmount(line.text, words: words) || line.listed
            || (line.text.u16Count <= shortLine && !sentenceEnd.contains(line.text.last ?? "."))
    }

    /// A bold line or heading inside a section that isn't an amount names a group ("Sauce:"),
    /// as does a bold step title; a numbered item never does.
    private static func written(_ line: Line, _ words: LanguageWords) -> String {
        let t = line.text
        let group = line.strong && (line.number == nil || line.step) && !t.hasSuffix(":") && t.u16Count <= 50
            && !sentenceEnd.contains(t.last ?? ".") && !isAmount(t, words: words)
        return group ? t + ":" : t
    }

    /// `text` split with `words`' language only (English by default); see `detectAndSplit`.
    static func split(_ text: String, words: LanguageWords = .english) -> SplitRecipe? {
        let w = self.words(words)
        var ingredients: [String] = []
        var instructions: [String] = []
        var yield: String?
        var prep: String?
        var cook: String?
        var total: String?
        var state: Section?
        // The lines before any section, for an ingredients block with no header.
        var before: [Line] = []

        /// A labelled yield or time, taken; false for any other line.
        func labelled(_ line: String) -> Bool {
            if let m = w.yieldLine.matchEntire(line) {
                if yield == nil {
                    yield = w.yieldWhole.matchEntire(m[1]) != nil ? line : m[2].kTrimmed
                }
                return true
            }
            if w.yieldFor.matchEntire(line) != nil {
                if yield == nil { yield = line }
                return true
            }
            if let m = w.time.matchEntire(line) {
                let value = Durations.format(m[4], words: words)
                if !m[1].isEmpty {
                    if prep == nil { prep = value }
                } else if !m[3].isEmpty {
                    if total == nil { total = value }
                } else if cook == nil {
                    cook = value
                }
                return true
            }
            return false
        }

        /// The ingredients with no header: the lines just above the steps, read upwards until
        /// one doesn't read like an ingredient.
        func ingredientsAbove() -> Bool {
            let block = Array(before.reversed().prefix(while: { readsLikeIngredient($0, words) }).reversed())
                .drop { !isAmount($0.text, words: words) && !$0.listed }
            if block.filter({ isAmount($0.text, words: words) }).count < minAmounts { return false }
            ingredients += block.map { written($0, words) }
            before.removeAll()
            return true
        }

        for line in lines(text, words: words) {
            if line.text.isEmpty && !line.step { continue }
            if let header = header(line, words: words) {
                if header.section == .instructions && ingredients.isEmpty { _ = ingredientsAbove() }
                state = header.section
                before.removeAll()
                switch header.section {
                case .ingredients: if let label = header.label { ingredients.append(label) }
                case .instructions: if let label = header.label { instructions.append(label) }
                case .end: break
                }
                continue
            }
            // A numbered list starting at 1 (or "Step 1") with no header over it is the steps.
            if line.number == 1 && !isAmount(line.text, words: words) {
                if (state == .ingredients && ingredients.contains { !$0.hasSuffix(":") })
                    || (state == nil && ingredientsAbove()) {
                    state = .instructions
                }
            }
            if line.text.isEmpty || isPointer(line.text) { continue } // a bare "Step 2", or a link
            switch state {
            case .ingredients: if !labelled(line.text) { ingredients.append(written(line, words)) }
            case .instructions: instructions.append(written(line, words))
            case .end: break
            case nil: if !labelled(line.text) { before.append(line) }
            }
        }
        if !ingredients.contains(where: { !$0.hasSuffix(":") }) || !instructions.contains(where: { !$0.hasSuffix(":") }) {
            return nil
        }
        return SplitRecipe(
            ingredients: ingredients, instructions: instructions, yield: yield,
            prepTime: prep, cookTime: cook, totalTime: total
        )
    }
}
