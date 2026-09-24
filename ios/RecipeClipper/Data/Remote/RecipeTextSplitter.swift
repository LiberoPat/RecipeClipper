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

/// Turns free text laid out as a recipe (a Reddit post body, or a comment transcribing a photo
/// of a recipe card) into ingredients and steps. Ported line for line from Android's
/// `RecipeTextSplitter`; its doc comment has the full rules.
///
/// **It never guesses.** Text splits only when a line that is nothing but an ingredients
/// header and a line that is nothing but an instructions header both appear, each with lines
/// under it. Sections may repeat and come in either order; notes ("Notes", "Tips", "Edit:")
/// and the story before the first header are dropped, except a labelled yield or time.
///
/// Pure: text in, data out.
enum RecipeTextSplitter {

    enum Section { case ingredients, instructions, end }

    private static let ingredientHeaders: Set<String> = [
        "ingredients", "ingredient", "ingredient list", "ingredients list",
        "you will need", "you'll need", "what you need", "what you'll need", "what you will need",
    ]
    private static let instructionHeaders: Set<String> = [
        "instructions", "directions", "method", "steps", "preparation", "procedure",
        "how to make", "how to make it",
    ]
    private static let endHeaders: Set<String> = [
        "notes", "note", "recipe notes", "tips", "tip", "nutrition", "nutrition facts",
        "source", "sources",
    ]

    private static let forHeader = JRegex(#"^(ingredients|instructions|directions|method)\s+for\s+.+:$"#)
    private static let editLine = JRegex(#"^(?:edit|update|eta)\b[^:]{0,12}:"#, ignoreCase: true)
    private static let trailingParenthetical = JRegex(#"\s*\([^)]*\)$"#)

    /// The section a cleaned line opens, or nil for an ordinary line.
    static func section(_ line: String) -> Section? {
        if editLine.containsMatch(in: line) { return .end }
        let lower = line.lowercased().kTrimmed
        if forHeader.matchEntire(lower) != nil {
            return lower.hasPrefix("ingredients") ? .ingredients : .instructions
        }
        let base = trimEnd(
            trailingParenthetical.replace(trimEnd(lower, [":", " ", "-", "–", "—"]), with: ""),
            [":", " "]
        )
        if ingredientHeaders.contains(base) { return .ingredients }
        if instructionHeaders.contains(base) { return .instructions }
        if endHeaders.contains(base) { return .end }
        return nil
    }

    /// Kotlin's `trimEnd(vararg chars)`.
    private static func trimEnd(_ s: String, _ chars: Set<Character>) -> String {
        var out = s
        while let last = out.last, chars.contains(last) { out.removeLast() }
        return out
    }

    private static let blockquote = JRegex(#"^(?:>\s*)+"#)
    private static let heading = JRegex(#"^#{1,6}(?!\d)\s*"#)
    private static let rule = JRegex(#"^(?:[-*_]\s*){3,}$"#)
    private static let bullet = JRegex(#"^[-*+•·▪◦]\s+"#)
    private static let numbered = JRegex(#"^\(?\d{1,2}[.)]\s+"#)
    private static let stepLabel = JRegex(#"^step\s*\d{1,2}\s*[:.)\-–—]\s*"#, ignoreCase: true)
    private static let checkbox = JRegex(#"^\[[ xX]?]\s+"#)
    private static let link = JRegex(#"\[([^\]]+)]\((?:[^()]|\([^)]*\))*\)"#)
    private static let emphasis = JRegex(#"\*\*|__|~~"#)
    private static let escape = JRegex(#"\\([\\`*_{}\[\]()#+\-.!>~^|])"#)

    /// One line of Markdown as plain text: `stripHtml` first (which also trims and collapses
    /// whitespace), then the quote, heading, rule, list marker, link, emphasis and escape
    /// syntax removed. "1.5 cups" keeps its number: a list marker needs a space after it.
    static func cleanLine(_ raw: String) -> String {
        var s = JsonLdRecipeParser.stripHtml(raw)
        s = blockquote.replace(s, with: "")
        s = heading.replace(s, with: "")
        if rule.matchEntire(s) != nil { return "" }
        s = bullet.replace(s, with: "")
        s = checkbox.replace(s, with: "")
        s = numbered.replace(s, with: "")
        s = stepLabel.replace(s, with: "")
        s = link.replace(s) { $0[1] }
        s = emphasis.replace(s, with: "")
        s = s.kTrimmed
        if s.u16Count > 2, let first = s.first, first == "*" || first == "_", s.last == first {
            s = String(s.dropFirst().dropLast())
        }
        s = escape.replace(s) { $0[1] }
        return s.kTrimmed
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

        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        for raw in normalized.components(separatedBy: "\n") {
            let line = cleanLine(raw)
            if line.isEmpty { continue }
            if let header = section(line) {
                state = header
                continue
            }
            switch state {
            case .ingredients: ingredients.append(line)
            case .instructions: instructions.append(line)
            case .end: break
            case nil:
                if let m = yieldLine.matchEntire(line), yield == nil {
                    let label = m[1].lowercased()
                    yield = (label == "serves" || label == "makes") ? line : m[2].kTrimmed
                }
                if let m = timeLine.matchEntire(line) {
                    let label = m[1].lowercased()
                    let value = JsonLdRecipeParser.formatDuration(m[2])
                    if label.hasPrefix("prep") {
                        if prep == nil { prep = value }
                    } else if label.hasPrefix("total") {
                        if total == nil { total = value }
                    } else if cook == nil {
                        cook = value
                    }
                }
            }
        }
        if ingredients.isEmpty || instructions.isEmpty { return nil }
        return SplitRecipe(
            ingredients: ingredients, instructions: instructions, yield: yield,
            prepTime: prep, cookTime: cook, totalTime: total
        )
    }
}
