import Foundation

/// Which part of a page's text goes to the on-device model (#103). Pure; Android's
/// `RecipeTextWindow`, rule for rule: the ingredients heading followed, before the next heading,
/// by the most ingredient-looking lines (more if the steps heading follows), else the densest
/// run of such lines, else nil (the model isn't asked). The window keeps a few lines before the
/// anchor, then as many after it as fit, and starts with the page's title.
enum RecipeTextWindow {
    private static let headingMax = 60
    private static let lookAhead = 40
    private static let leadLines = 12
    private static let ingredientMax = 100
    private static let shortLine = 40
    private static let vulgarFractions = Set("¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞".unicodeScalars)

    private static let headings: (ingredients: [String], steps: [String]) = {
        let all = LanguageWords.shipped.compactMap { LanguageWords.forTag($0) }
        return (all.flatMap { $0.strings("headings", "ingredients") }, all.flatMap { $0.strings("headings", "steps") })
    }()

    /// The window's text, lines joined by "\n", at most `maxChars` long; nil if no recipe shows.
    static func window(_ page: PageText, maxChars: Int) -> String? {
        let lines = page.lines.map { $0.kTrimmed }.filter { !$0.isEmpty }
        guard let anchor = anchor(lines) else { return nil }
        let title = page.title.map { $0.kTrimmed }.flatMap { !$0.isEmpty && length($0) < maxChars / 4 ? $0 : nil }
        var budget = maxChars - (title.map { length($0) + 1 } ?? 0)

        var lead: [String] = []
        var leadChars = 0
        var i = anchor - 1
        while i >= 0, lead.count < leadLines, leadChars + length(lines[i]) + 1 <= maxChars / 5 {
            lead.insert(lines[i], at: 0); leadChars += length(lines[i]) + 1; i -= 1
        }
        budget -= leadChars
        var body: [String] = []
        var j = anchor
        while j < lines.count, length(lines[j]) + 1 <= budget {
            body.append(lines[j]); budget -= length(lines[j]) + 1; j += 1
        }
        if body.isEmpty { return nil }
        let window = lead + body
        let withTitle = title.map { window.contains($0) ? window : [$0] + window } ?? window
        return withTitle.joined(separator: "\n")
    }

    /// The index of the line the recipe starts at, or nil.
    static func anchor(_ lines: [String]) -> Int? {
        let looks = lines.map(looksLikeIngredient)
        var best: Int?
        var bestScore = 1
        for (i, line) in lines.enumerated() where isHeading(line, headings.ingredients) {
            var ahead = 0
            var steps = false
            var j = i + 1
            while j < lines.count, j <= i + lookAhead {
                if isHeading(lines[j], headings.steps) { steps = true; break }
                if isHeading(lines[j], headings.ingredients) { break }
                if looks[j] { ahead += 1 }
                j += 1
            }
            let score = ahead + (steps ? 2 : 0)
            if ahead >= 1, score > bestScore { best = i; bestScore = score }
        }
        if let best { return best }
        var dense: Int?
        var denseCount = 2
        for i in lines.indices where looks[i] {
            let count = (i..<min(lines.count, i + 10)).filter { looks[$0] }.count
            if count > denseCount { dense = i; denseCount = count }
        }
        return dense
    }

    /// A short line that starts with one of `words` as whole words ("Ingredients:", "INGREDIENTS").
    static func isHeading(_ line: String, _ words: [String]? = nil) -> Bool {
        if length(line) > headingMax { return false }
        let text = Array(line.lowercased().utf16).drop { !PageRecipeCheck.isLetterOrDigit($0) }
        return (words ?? headings.ingredients + headings.steps).contains { word in
            let w = Array(word.utf16)
            guard text.count >= w.count, Array(text.prefix(w.count)) == w else { return false }
            return text.count == w.count || !PageRecipeCheck.isLetterOrDigit(text[text.startIndex + w.count])
        }
    }

    /// An ingredients heading in any shipped language; `PageRecipeCheck` cuts a page into cards with it (#128).
    static func isIngredientsHeading(_ line: String) -> Bool { isHeading(line, headings.ingredients) }

    static func isStepsHeading(_ line: String) -> Bool { isHeading(line, headings.steps) }

    /// "2 cups flour", "• ½ tsp salt", "Mehl 200 g", "砂糖 大さじ2": an amount first, or a short line with one.
    static func looksLikeIngredient(_ line: String) -> Bool {
        if length(line) > ingredientMax { return false }
        let text = Array(line.utf16).drop { !PageRecipeCheck.isLetterOrDigit($0) && !isFraction($0) }
        guard let first = text.first else { return false }
        return PageRecipeCheck.isDigit(first) || isFraction(first)
            || (text.count <= shortLine && text.contains(where: PageRecipeCheck.isDigit))
    }

    private static func isFraction(_ c: UInt16) -> Bool { Unicode.Scalar(c).map(vulgarFractions.contains) ?? false }

    /// Length as Kotlin counts it: UTF-16 units.
    private static func length(_ s: String) -> Int { s.utf16.count }
}
