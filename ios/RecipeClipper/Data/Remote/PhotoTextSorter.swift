import Foundation

/// One line of text the device read from a photo (#198), in reading order, with the
/// recogniser's `confidence` (0 to 1) when it gives one. Android's `PhotoLine`.
struct PhotoLine: Equatable, Sendable {
    let text: String
    var confidence: Float? = nil
}

/// What `PhotoTextSorter.sort` made of a photo's lines: the recipe editor's content, before the
/// cook checks it. When `sorted` is false the splitter found no recipe, and `ingredients` holds
/// every line read, as read, for the cook to sort by hand.
struct PhotoReading: Equatable {
    let ingredients: [String]
    let instructions: [String]
    var yield: String? = nil
    var prepTime: String? = nil
    var cookTime: String? = nil
    var totalTime: String? = nil
    let sorted: Bool
    /// The lines shown to check: text the recogniser was unsure of, or an amount shaped like a misreading.
    var uncertain: [String] = []
    /// The language the lines' words clearly say (#208), which they were read in; nil when nothing
    /// was clear and English was used.
    var language: String? = nil

    /// Nothing at all was read.
    var isEmpty: Bool { ingredients.isEmpty && instructions.isEmpty }
}

/// Sorts the lines read from a photo (#198) with the same splitter that reads a typed Reddit
/// post (`RecipeTextSplitter`), so headers, lists and numbered steps are recognised the same way.
///
/// **Nothing is invented or corrected.** Every line is the recogniser's text (only trimmed, and
/// cleaned by the splitter as a typed post's lines are); an amount it misread stays misread, and
/// the lines it was unsure of, or whose amount is shaped like a misreading ("11/2"), are named in
/// `uncertain` for the cook to check against the photo.
/// When the splitter finds no recipe the lines are returned unsorted, never guessed at.
///
/// The lines are read in the language their words say (#208, detected as a page's is, #14), else
/// English: that language's headers, and its unit words for `suspect`.
///
/// Pure: lines in, data out. A line-for-line port of Android's `PhotoTextSorter.kt`.
enum PhotoTextSorter {
    /// Below this, a line is marked "check this". Vision's accurate recogniser answers 0.3 for
    /// text it is unsure of and 0.5 or 1 otherwise; ML Kit's scores fall on the same scale.
    static let lowConfidence: Float = 0.5

    private static let minPart = 4

    static func sort(_ lines: [PhotoLine]) -> PhotoReading {
        let read = lines.map { PhotoLine(text: $0.text.kTrimmed, confidence: $0.confidence) }.filter { !$0.text.isEmpty }
        let unsure = read.filter { ($0.confidence ?? 1) < lowConfidence }.map(\.text)
        let text = read.map(\.text).joined(separator: "\n")
        let language = RecipeTextSplitter.languageOf(text)
        let words = RecipeTextSplitter.wordsFor(language)
        let split = read.isEmpty ? nil : RecipeTextSplitter.split(text, words: words)
        guard let split, !(split.ingredients.isEmpty && split.instructions.isEmpty) else {
            let all = read.map(\.text)
            return PhotoReading(
                ingredients: all, instructions: [], sorted: false,
                uncertain: toCheck(all, unsure: unsure, words: words), language: language
            )
        }
        return PhotoReading(
            ingredients: split.ingredients,
            instructions: split.instructions,
            yield: split.yield,
            prepTime: split.prepTime,
            cookTime: split.cookTime,
            totalTime: split.totalTime,
            sorted: true,
            uncertain: toCheck(split.ingredients + split.instructions, unsure: unsure, words: words),
            language: language
        )
    }

    /// The `shown` lines to check: those `marked` from text the recogniser was unsure of, then any
    /// other whose amount looks misread (`suspect`), however sure the recogniser was.
    private static func toCheck(_ shown: [String], unsure: [String], words: LanguageWords) -> [String] {
        let low = Set(marked(shown, unsure: unsure))
        var out: [String] = []
        for line in shown where !out.contains(line) && (low.contains(line) || suspect(line, words: words)) {
            out.append(line)
        }
        return out
    }

    /// True when `line`'s amount has a shape a recogniser gives for a misread one (#198), so the
    /// cook checks it against the photo. The text is never changed:
    /// - an improper fraction over 2 to 8, "11/2" or "31/3": most likely "1 1/2" or "3 1/3" with
    ///   its space lost (and the scaler would read 5½);
    /// - a digit beside a letter that looks like one: "l/2", "O.5", "1/Z", "1O", "35o°F";
    /// - a unit glued to the word after it, where the scaler reads no unit: "1 cupraisins", in
    ///   `words`' language ("2 ELZucker", "1 tazaharina"); never in one written without spaces.
    static func suspect(_ line: String, words: LanguageWords = .english) -> Bool {
        let improper = improperFraction.findAll(line).contains { m in
            // The numerator without leading zeros: two digits or more is always above 2 to 8.
            let n = String(m[1].drop { $0 == "0" })
            guard let d = Int(m[2]), (2...8).contains(d) else { return false }
            return n.count > 1 || (!n.isEmpty && Int(n)! > d)
        }
        return improper || lookAlike.containsMatch(in: line) || gluedUnit(line, words: words)
    }

    private static func gluedUnit(_ line: String, words: LanguageWords) -> Bool {
        guard words.spaced else { return false }
        let p = IngredientScaler.patterns(words)
        guard let lead = p.leading.find(line) else { return false }
        let rest = line.u16Substring(from: lead.end)
        return !p.unitAtStart.containsMatch(in: rest) && RecipeTextSplitter.gluedUnit(rest, words: words)
    }

    // "11/2": digits, a slash, one digit, and no more digits or slashes around it.
    private static let improperFraction = JRegex(#"(?<![\d/⁄.,])(\d+)[/⁄](\d)(?![\d/⁄])"#)

    // A letter read for a digit, or a digit's neighbour read as a letter, beside a fraction's
    // slash or a decimal point: "l/2", "O.5", "l2", "1/Z", "1.O", "1O", "35o°F". A lowercase "o"
    // or "l" straight after a number is not one ("1oz", "1l"), only one standing alone.
    private static let lookAlike = JRegex(
        #"(?<![\p{L}\d])[lIOo|][/⁄.,]?\d|\d[/⁄.,][lIOoZS](?!\p{L})|\d[Oo](?!\p{L})"#
    )

    // A unit word run into the next ("cupraisins", "tbspsugar") is the language's splitter.json
    // gluedUnits (RecipeTextSplitter.gluedUnit). Units of one or two letters ("g", "c", "l",
    // "oz") are left out, since "2 green onions" starts with one; "cupcake" is a word of its own.

    /// The `shown` lines that hold, or are held in, a line the recogniser was unsure of. The
    /// splitter only takes markup off a line ("1." or "•"), so a line shown still contains the
    /// text it came from. A piece shorter than four characters ("1", "Mix") is matched only as
    /// a whole line, so it can't mark every line it happens to appear in.
    static func marked(_ shown: [String], unsure: [String]) -> [String] {
        guard !unsure.isEmpty else { return [] }
        var out: [String] = []
        for line in shown where !out.contains(line) {
            let hit = unsure.contains { u in
                line == u
                    || (u.utf16.count >= minPart && line.contains(u))
                    || (line.utf16.count >= minPart && u.contains(line))
            }
            if hit { out.append(line) }
        }
        return out
    }
}
