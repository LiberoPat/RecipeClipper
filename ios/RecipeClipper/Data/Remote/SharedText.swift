import Foundation

/// Text shared into the app (#306): a note, an email, a message. Where it goes (`route`) and what
/// the editor opens with (`read`). Ported line for line from Android's `SharedText`; its doc
/// comment has the rules (#334, #350): a recipe as schema.org JSON (what an AI chat writes, bare,
/// fenced or among prose; the first Recipe `JsonLdRecipeParser` finds, never its url or image)
/// comes first and opens the editor with its name; then a recipe (the text splits, or is the app's own shared text)
/// opens the editor even beside a link; a link wins only when the text is essentially just the
/// link; a list is every line bulleted, headings allowed; else the editor, as written. Nothing
/// shared is lost: the lines a split leaves out come back as leftovers. Blank text goes nowhere.
enum SharedText {

    /// Where shared text goes.
    enum Route: Equatable {
        /// The editor, on `reading`: sorted into a recipe, or every line as written.
        case editor(Reading)
        /// Today's import of the link: the text was essentially just the link.
        case link(String)
        /// #149's "Add this list".
        case list
        /// Nothing: blank text, or text too long for the editor that is neither a link nor a list.
        case none
    }

    /// `route` by name, for the corpus and the tests.
    enum Kind: Equatable { case recipe, link, list, text, ignored }

    /// Longer text isn't offered to the editor (Android's review keeps it in saved state).
    static let maxLength = 50_000

    /// A link with at most this many other lines, none longer than `captionLength`, is just a link.
    static let captionLines = 2
    static let captionLength = 160

    /// The longest unbulleted line a list may hold as a heading ("Groceries", "Produce").
    private static let headingLength = 40
    private static let sentenceEnd: Set<Character> = [".", "!", "?", "。", "！", "？"]

    /// Every line break but "\n": "\r\n", "\r", the vertical tab, form feed, NEL, LS and PS.
    private static let separator = JRegex("\r\n|[\r\u{000B}\u{000C}\u{0085}\u{2028}\u{2029}]")

    /// `text` with every line break as "\n".
    static func normalize(_ text: String) -> String { separator.replace(text, with: "\n") }

    /// The first http(s) link in `text`, as shared (the repository cleans it).
    static func link(_ text: String) -> String? { UrlInput.extractSharedUrl(text) }

    static func route(_ text: String) -> Route {
        let normalized = normalize(text)
        if normalized.kIsBlank { return .none }
        let reading = normalized.u16Count <= maxLength ? read(normalized) : nil
        if let reading, reading.sorted { return .editor(reading) }
        let lines = ReceivedList.allLines(normalized)
        let link = link(normalized)
        if let link, justALink(lines) { return .link(link) }
        if isList(lines) { return .list }
        if let reading { return .editor(reading) }
        return link.map { .link($0) } ?? .none
    }

    static func kind(_ text: String) -> Kind {
        switch route(text) {
        case .editor(let reading): return reading.sorted ? .recipe : .text
        case .link: return .link
        case .list: return .list
        case .none: return .ignored
        }
    }

    /// Rule 2: the links taken out, what's left is at most a short title or caption.
    private static func justALink(_ lines: [String]) -> Bool {
        let others = lines.map { UrlInput.sharedLink.replace($0, with: "").kTrimmed }.filter { !$0.isEmpty }
        return others.count <= captionLines && others.allSatisfy { $0.u16Count <= captionLength }
    }

    /// Rule 3: every line bulleted, or a heading with a bulleted line somewhere after it.
    private static func isList(_ lines: [String]) -> Bool {
        guard let lastBullet = lines.lastIndex(where: bulleted) else { return false }
        return lines.enumerated().allSatisfy { i, line in
            bulleted(line) || i < lastBullet && line.u16Count <= headingLength
                && !sentenceEnd.contains(line.last!) && !line.contains("http")
        }
    }

    /// `ReceivedList`'s bullets.
    private static let bullets: Set<Character> = ["-", "*", "•", "◦", "▪", "·", "–", "—"]

    private static func bulleted(_ line: String) -> Bool {
        let chars = Array(line)
        return chars.count >= 2 && bullets.contains(chars[0]) && chars[1].isWhitespace && !String(chars.dropFirst()).kIsBlank
    }

    /// What the editor opens with: the app's own shared text read back (`appShared`), else the
    /// split recipe when the text splits, with the lines the split left out as `leftovers`; else
    /// every non-blank line, trimmed and otherwise as written, as the steps (never scaled,
    /// converted, bought or used up). No name either way.
    static func read(_ text: String) -> Reading {
        let normalized = normalize(text)
        if let json = jsonRecipe(normalized) { return json }
        if let shared = appShared(normalized) { return shared }
        let lines = normalized.components(separatedBy: "\n")
        if let found = RecipeTextSplitter.detectAndSplitLines(normalized, plain: true) {
            var recipe = found.split.recipe
            recipe.language = found.language
            return Reading(recipe: recipe, sorted: true, leftovers: found.split.leftover.map { lines[$0].kTrimmed })
        }
        let all = ReceivedList.allLines(normalized)
        let language = RecipeTextSplitter.languageOf(normalized)
        return Reading(
            recipe: SplitRecipe(
                ingredients: [], instructions: all, yield: nil, prepTime: nil, cookTime: nil,
                totalTime: nil, language: language
            ),
            sorted: false, leftovers: []
        )
    }

    /// `recipe` is what the editor's fields start with; its language, the text's (#208).
    /// `leftovers` are the lines of a sorted text that ended in no field, as written, in order:
    /// the story, "Notes:" and what's under it, a link; the review shows them as the note.
    struct Reading: Hashable, Codable {
        let recipe: SplitRecipe
        let sorted: Bool
        var leftovers: [String] = []
        /// The recipe's name when the text carried one (a schema.org Recipe, #350); else blank.
        var name: String = ""
        /// Read from a schema.org Recipe's JSON (#350): the editor saves it as from a chat (#355).
        var fromChat: Bool = false
        /// The JSON's `isBasedOn` (#354), as written: the `sourceUrl` of the recipe it changed, or
        /// nil. Only a claim: the repository finds the recipe, and an unmatched one is ignored.
        var basedOn: String? = nil
    }

    /// How many places in the text that open a JSON value are tried before giving up.
    private static let maxJsonCandidates = 20

    /// A bracket, quote or backslash as the UTF-16 unit the scan compares.
    private static func u(_ c: Unicode.Scalar) -> UInt16 { UInt16(c.value) }

    /// Rule 0 (#350): the first `{...}` or `[...]` in the text that holds a schema.org Recipe, read
    /// by `JsonLdRecipeParser` (never a second reader). See the Kotlin.
    private static func jsonRecipe(_ text: String) -> Reading? {
        guard text.range(of: "recipe", options: .caseInsensitive) != nil else { return nil }
        let units = Array(text.utf16)
        let ns = text as NSString
        var tried = 0
        var i = 0
        while i < units.count && tried < maxJsonCandidates {
            if units[i] != u("{") && units[i] != u("[") { i += 1; continue }
            tried += 1
            let end = jsonEnd(units, i)
            if end < 0 { i += 1; continue }
            let block = ns.substring(with: NSRange(location: i, length: end - i))
            if let recipe = JsonLdRecipeParser.parse([block], sourceUrl: "") {
                let split = SplitRecipe(
                    ingredients: recipe.ingredients, instructions: recipe.instructions, yield: recipe.yield,
                    prepTime: recipe.prepTime, cookTime: recipe.cookTime, totalTime: recipe.totalTime,
                    language: recipe.language
                )
                return Reading(
                    recipe: split, sorted: true, leftovers: [], name: recipe.name, fromChat: true,
                    basedOn: JsonLdRecipeParser.isBasedOn([block])
                )
            }
            i = end
        }
        return nil
    }

    /// The bracket at `start` opens something that reads as JSON: a value, a key or a nested
    /// bracket comes next.
    static func looksLikeJson(_ units: [UInt16], _ start: Int) -> Bool {
        var j = start + 1
        while j < units.count && (units[j] == u(" ") || units[j] == u("\n") || units[j] == u("\t") || units[j] == u("\r")) { j += 1 }
        return j < units.count && (units[j] == u("{") || units[j] == u("[") || units[j] == u("\""))
    }

    private static let recipeType = JRegex(#""@type"\s*:\s*(?:\[\s*)?"Recipe""#, ignoreCase: true)

    /// An AI's answer that stopped inside its recipe JSON (#352): a `{` or `[` that reads as JSON
    /// and never closes, with a schema.org Recipe's `"@type": "Recipe"` somewhere after it. See the
    /// Kotlin's `isCutOff`.
    static func isCutOff(_ text: String) -> Bool {
        let normalized = normalize(text)
        if normalized.u16Count > maxLength { return false }
        let units = Array(normalized.utf16)
        let ns = normalized as NSString
        var tried = 0
        var i = 0
        while i < units.count && tried < maxJsonCandidates * 5 {
            if units[i] != u("{") && units[i] != u("[") { i += 1; continue }
            tried += 1
            let end = jsonEnd(units, i)
            if end >= 0 { i = end; continue }
            if looksLikeJson(units, i) && recipeType.containsMatch(in: ns.substring(from: i)) { return true }
            i += 1
        }
        return false
    }

    /// The index after the bracket that closes the one at `start`, or -1. Strings are skipped.
    static func jsonEnd(_ units: [UInt16], _ start: Int) -> Int {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while i < units.count {
            let c = units[i]
            i += 1
            if inString {
                if escaped {
                    escaped = false
                } else if c == u("\\") {
                    escaped = true
                } else if c == u("\"") {
                    inString = false
                }
                continue
            }
            switch c {
            case u("\""): inString = true
            case u("{"), u("["): depth += 1
            case u("}"), u("]"):
                depth -= 1
                if depth == 0 { return i }
            default: break
            }
        }
        return -1
    }

    /// One UI language's words in the app's own shared text (`splitter.json`'s `shareText`),
    /// compared with a no-break space ("Portions : 4") as a space: an app in between may swap it.
    private struct ShareWords {
        let ingredients: String
        let instructions: String
        /// "Serves 4", "Makes 3 (originally 6)", "Portionen: 4".
        let yieldLine: JRegex
        /// One entry of the times line: "Prep 10m"; group 1 prep, 2 cook, 3 total, 4 the time.
        let timeEntry: JRegex

        init(_ table: SharedTables.Table) {
            func word(_ key: String) -> String { spaced(table[key] as? String ?? "") }
            func prefix(_ key: String) -> String {
                NSRegularExpression.escapedPattern(for: String(word(key).components(separatedBy: "{n}").first ?? ""))
            }
            func escaped(_ key: String) -> String { NSRegularExpression.escapedPattern(for: word(key)) }
            ingredients = word("ingredients")
            instructions = word("instructions")
            yieldLine = JRegex("^(?:\(prefix("serves"))|\(prefix("makes")))\\d")
            timeEntry = JRegex("^(?:(\(escaped("prep")))|(\(escaped("cook")))|(\(escaped("total"))))\\s+(\\S.*)$")
        }
    }

    private static func spaced(_ s: String) -> String { s.replacingOccurrences(of: "\u{00A0}", with: " ") }

    private static let shareWords: [ShareWords] = LanguageWords.shipped.compactMap(shareTable).map(ShareWords.init)

    private static func shareTable(_ language: String) -> SharedTables.Table? {
        LanguageWords.forTag(language)?.table("splitter")["shareText"] as? SharedTables.Table
    }

    /// The words the table holds for the app's own shared text in `language`'s UI ("serves" is
    /// "Serves {n}"), for the tests that pin them to each platform's strings; nil for a language
    /// the UI doesn't speak.
    static func shareLabels(_ language: String) -> [String: String]? {
        guard let table = shareTable(language) else { return nil }
        return Dictionary(uniqueKeysWithValues: shareLabelKeys.map { ($0, table[$0] as? String ?? "") })
    }

    private static let shareLabelKeys = ["ingredients", "instructions", "serves", "makes", "prep", "cook", "total"]

    /// "1. Mix." as the app numbers a shared step.
    private static let stepNumber = JRegex(#"^\d{1,3}\.\s+"#)

    /// Rule 6: the app's own shared text (`RecipeShareText`, #149) read back, whichever of the
    /// six UI languages the sender's phone wrote its words in. See the Kotlin.
    private static func appShared(_ text: String) -> Reading? {
        let lines = text.components(separatedBy: "\n").map(\.kTrimmed)
        let compared = lines.map(spaced)
        for w in shareWords {
            guard let i = compared.firstIndex(of: w.ingredients) else { continue }
            guard let j = compared.indices.first(where: { $0 > i && compared[$0] == w.instructions }) else { continue }
            let ingredients = lines[(i + 1)..<j].filter { !$0.isEmpty }
            let steps = lines[(j + 1)...].filter { !$0.isEmpty }.map { stepNumber.replace($0, with: "") }
            if ingredients.isEmpty || steps.isEmpty { continue }
            var yield: String?
            var times: [String?]?
            var leftovers: [String] = []
            for line in lines[0..<i] where !line.isEmpty {
                let found = times == nil ? timesLine(spaced(line), w) : nil
                if yield == nil, w.yieldLine.containsMatch(in: spaced(line)) {
                    yield = line
                } else if let found {
                    times = found
                } else {
                    leftovers.append(line)
                }
            }
            let t = times ?? [nil, nil, nil]
            let recipe = SplitRecipe(
                ingredients: Array(ingredients), instructions: steps, yield: yield, prepTime: t[0], cookTime: t[1],
                totalTime: t[2], language: RecipeTextSplitter.languageOf(text)
            )
            return Reading(recipe: recipe, sorted: true, leftovers: leftovers)
        }
        return nil
    }

    /// "Prep 10m · Cook 20m · Total 30m" as [prep, cook, total]; nil unless every entry is one.
    private static func timesLine(_ line: String, _ w: ShareWords) -> [String?]? {
        var found: [String?] = [nil, nil, nil]
        for entry in line.components(separatedBy: " · ") {
            guard let m = w.timeEntry.matchEntire(entry.kTrimmed) else { return nil }
            let slot = (1...3).first { !m[$0].isEmpty }! - 1
            if found[slot] != nil { return nil }
            found[slot] = m[4]
        }
        return found
    }
}
