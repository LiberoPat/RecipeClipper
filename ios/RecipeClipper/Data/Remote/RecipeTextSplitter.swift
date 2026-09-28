import Foundation

/// What `RecipeTextSplitter` found in a block of text. The name comes from elsewhere (a
/// Reddit post's title), so it isn't here.
struct SplitRecipe: Equatable {
    let ingredients: [String]
    let instructions: [String]
    let yield: String?
    let prepTime: String?
    let cookTime: String?
    let totalTime: String?
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

    private static let ingredientHeaders: Set<String> = [
        "ingredients", "ingredient", "ingredient list", "ingredients list",
        "you will need", "you'll need", "what you need", "what you'll need", "what you will need",
        "things you'll need", "things you will need",
    ]
    private static let instructionHeaders: Set<String> = [
        "instructions", "directions", "method", "steps", "preparation", "procedure",
        "how to make", "how to make it",
    ]
    private static let endHeaders: Set<String> = [
        "notes", "note", "recipe notes", "tips", "tip", "nutrition", "nutrition facts",
        "source", "sources", "equipment",
    ]

    /// The last word of a header set apart as one ("**Dry ingredients**", "COOKING STEPS").
    private static let keywords: [String: Section] = [
        "ingredients": .ingredients, "ingredient": .ingredients,
        "instructions": .instructions, "instruction": .instructions,
        "directions": .instructions, "direction": .instructions,
        "method": .instructions, "steps": .instructions,
        "preparation": .instructions, "procedure": .instructions,
        "notes": .end, "note": .end, "tips": .end, "tip": .end,
        "equipment": .end,
    ]

    /// Words before the keyword that name no group, so the header adds no group heading.
    private static let plainWords: Set<String> = Set([
        "the", "main", "all", "full", "basic", "recipe", "cooking", "baking", "needed", "list",
        "amounts", "amount", "required", "used", "easy", "simple", "quick", "your", "my", "our",
        "step", "by", "step-by-step", "and", "&", "+", "-", "–",
    ]).union(keywords.keys)

    /// A line that starts with one of these is a step or a sentence, never a header.
    private static let verbs: Set<String> = [
        "add", "assemble", "bake", "beat", "blend", "check", "chop", "combine", "cook", "cream",
        "cut", "follow", "fold", "gather", "get", "heat", "measure", "melt", "mix", "make", "place",
        "pour", "prep", "prepare", "put", "read", "repeat", "season", "serve", "set", "sift",
        "stir", "toss", "use", "weigh", "whisk",
    ]

    /// One-word headers a typo away from the real word ("Ingredeints", "Intructions").
    private static let misspelt: [(String, Section)] = [
        ("ingredients", .ingredients),
        ("instructions", .instructions),
        ("directions", .instructions),
    ]

    private static let forHeader = JRegex(#"^(ingredients|instructions|directions|method)\s+for\s+.+:$"#)
    private static let editLine = JRegex(#"^(?:edit|update|eta)\b[^:]{0,12}:"#, ignoreCase: true)
    /// "(serves 4)", "<for 3~4 people>", "[metric]" at the end of a header.
    private static let trailingParenthetical = JRegex(#"\s*[(<\[][^)>\]]*[)>\]]$"#)
    private static let wordBreak = JRegex(#"[\s/]+"#)

    /// The header `line` is, or nil for an ordinary line. A numbered item or a step never is.
    static func header(_ line: Line) -> Header? {
        if line.number != nil || line.step { return nil }
        let text = line.text
        if editLine.containsMatch(in: text) { return Header(section: .end) }
        let lower = text.lowercased().kTrimmed.replacingOccurrences(of: "’", with: "'")
        if forHeader.matchEntire(lower) != nil {
            return Header(section: lower.hasPrefix("ingredients") ? .ingredients : .instructions)
        }
        let base = bare(lower)
        if ingredientHeaders.contains(base) { return Header(section: .ingredients) }
        if instructionHeaders.contains(base) { return Header(section: .instructions) }
        if endHeaders.contains(base) { return Header(section: .end) }
        if !line.headerLike { return nil }
        let words = wordBreak.split(base).filter { !$0.isEmpty }
        if (2...4).contains(words.count) {
            let modifiers = words.dropLast()
            if let section = keywords[words[words.count - 1]],
               !modifiers.contains(where: verbs.contains),
               modifiers.allSatisfy({ $0.allSatisfy { $0.isLetter || "'&+-–".contains($0) } }) {
                let plain = section == .end || modifiers.allSatisfy(plainWords.contains)
                return Header(section: section, label: plain ? nil : trimEnd(text, [":", " "]) + ":")
            }
            // "Ingredients needed", "Ingredient amounts"
            if words[0] == "ingredients" || words[0] == "ingredient", words.dropFirst().allSatisfy(plainWords.contains) {
                return Header(section: .ingredients)
            }
        }
        if words.count == 1, base.u16Count >= 8,
           let match = misspelt.first(where: { editDistance(base, $0.0) <= 2 }) {
            return Header(section: match.1)
        }
        return nil
    }

    /// The section a line of Markdown opens, or nil for an ordinary line.
    static func section(_ raw: String) -> Section? { header(line(raw))?.section }

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

    fileprivate static func isCapitals(_ text: String) -> Bool {
        let letters = text.filter(\.isLetter)
        return letters.count >= 3 && !letters.contains(where: \.isLowercase)
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
    private static let stepLabel = JRegex(#"^step\s*(\d{1,2})\s*(?:[:.)\-–—]\s*|$)"#, ignoreCase: true)
    private static let checkbox = JRegex(#"^\[[ xX]?]\s+"#)
    private static let link = JRegex(#"\[([^\]]+)]\((?:[^()]|\([^)]*\))*\)"#)
    private static let emphasis = JRegex(#"\*\*|__|~~"#)
    /// Italics inside a line ("Glaze *(for grilling)*"); a lone "*" (a footnote) stays.
    private static let italicRun = JRegex(#"(?<![A-Za-z0-9_*])\*(?![\s*])([^*]+?)(?<!\s)\*(?![A-Za-z0-9_*])"#)
    private static let escape = JRegex(#"\\([\\`*_{}\[\]()#+\-.!>~^|])"#)

    /// One line of Markdown as plain text: `stripHtml` first (which also trims and collapses
    /// whitespace), backslash escapes undone, then the quote, heading, rule, list marker, link
    /// and emphasis syntax removed. "1.5 cups" keeps its number: a list marker needs a space.
    static func line(_ raw: String) -> Line {
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
        if let m = stepLabel.find(s) { s = s.u16Substring(from: m.end).kTrimmed; step = true; number = Int(m[1]) }
        return Line(text: s, strong: isHeading || bold, italic: italic, listed: listed, number: number, step: step)
    }

    /// `line`'s text alone.
    static func cleanLine(_ raw: String) -> String { line(raw).text }

    /// Every line of `text`, cleaned, blank ones included.
    static func lines(_ text: String) -> [Line] {
        text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n").map(line)
    }

    private static let fractions = "½⅓⅔¼¾⅛⅜⅝⅞"
    private static let units = #"cups?|c|tsps?|teaspoons?|tbsps?|tbs|tablespoons?|fl\.?\s*oz|oz|ounces?|lbs?|pounds?|"# +
        #"g|grams?|kg|kilos?|kilograms?|ml|millilit(?:er|re)s?|l|lit(?:er|re)s?|quarts?|qts?|pints?|gallons?|"# +
        #"pinch(?:es)?|dash(?:es)?|cloves?|cans?|jars?|sticks?|handfuls?|bunch(?:es)?|sprigs?|slices?|pieces?|"# +
        #"packages?|packets?|pkgs?|heads?|stalks?|leaves|inch(?:es)?|cm"#

    /// An amount: a line that starts with a number or fraction, or has one before a unit.
    private static let amount = JRegex(
        #"^(?:about\s+|approx\.?\s+|~\s*)?[0-9"# + fractions + #"]|[0-9"# + fractions + #"]\s*(?:"# + units + #")\.?(?![A-Za-z])"#,
        ignoreCase: true
    )

    static func isAmount(_ text: String) -> Bool { amount.containsMatch(in: text) }

    /// The longest line that reads like an ingredient without an amount or list marker.
    private static let shortLine = 40

    /// An ingredients block with no header needs at least this many amounts.
    static let minAmounts = 2

    /// A line with a bare address in it is a pointer elsewhere, never an ingredient or a step.
    private static func isPointer(_ text: String) -> Bool { text.contains("http://") || text.contains("https://") }

    private static func readsLikeIngredient(_ line: Line) -> Bool {
        isAmount(line.text) || line.listed
            || (line.text.u16Count <= shortLine && !".!?".contains(line.text.last ?? "."))
    }

    /// A bold line or heading inside a section that isn't an amount names a group ("Sauce:"),
    /// as does a bold step title; a numbered item never does.
    private static func written(_ line: Line) -> String {
        let t = line.text
        let group = line.strong && (line.number == nil || line.step) && !t.hasSuffix(":") && t.u16Count <= 50
            && !".!?".contains(t.last ?? ".") && !isAmount(t)
        return group ? t + ":" : t
    }

    private static let yieldLine = JRegex(
        #"^(serves|makes|servings|yields?|portions)\b\s*:?\s*(\S.*)$"#, ignoreCase: true
    )
    private static let timeLine = JRegex(
        #"^(prep(?:aration)?(?:\s+time)?|cook(?:ing)?(?:\s+time)?|bake(?:\s+time)?|baking\s+time|total(?:\s+time)?)\s*:\s*(\S.*)$"#,
        ignoreCase: true
    )

    static func split(_ text: String) -> SplitRecipe? {
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
            if let m = yieldLine.matchEntire(line) {
                if yield == nil {
                    let label = m[1].lowercased()
                    yield = (label == "serves" || label == "makes") ? line : m[2].kTrimmed
                }
                return true
            }
            if let m = timeLine.matchEntire(line) {
                let label = m[1].lowercased()
                let value = Durations.format(m[2])
                if label.hasPrefix("prep") {
                    if prep == nil { prep = value }
                } else if label.hasPrefix("total") {
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
            let block = Array(before.reversed().prefix(while: readsLikeIngredient).reversed())
                .drop { !isAmount($0.text) && !$0.listed }
            if block.filter({ isAmount($0.text) }).count < minAmounts { return false }
            ingredients += block.map(written)
            before.removeAll()
            return true
        }

        for line in lines(text) {
            if line.text.isEmpty && !line.step { continue }
            if let header = header(line) {
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
            if line.number == 1 && !isAmount(line.text) {
                if (state == .ingredients && ingredients.contains { !$0.hasSuffix(":") })
                    || (state == nil && ingredientsAbove()) {
                    state = .instructions
                }
            }
            if line.text.isEmpty || isPointer(line.text) { continue } // a bare "Step 2", or a link
            switch state {
            case .ingredients: if !labelled(line.text) { ingredients.append(written(line)) }
            case .instructions: instructions.append(written(line))
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
