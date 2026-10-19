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
    /// The `uncertain` lines listed only because their amount looks too large for its unit
    /// (`PhotoTextSorter.implausible`, #281): shown with that reason, not the photo's.
    var large: [String] = []
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
            // Unsorted, any line may be an ingredient: its list markup goes before the size
            // check ("▢ 34 tsp salt"), and a step or a conversion ("48 tsp = 1 cup") is skipped.
            let checked = toCheck(all, unsure: unsure, words: words) { raw in
                let line = RecipeTextSplitter.line(raw, words: words)
                return line.step || line.text.contains("=") ? nil : line.text
            }
            return PhotoReading(
                ingredients: all, instructions: [], sorted: false,
                uncertain: checked.uncertain, large: checked.large, language: language
            )
        }
        let ingredients = Set(split.ingredients)
        let checked = toCheck(split.ingredients + split.instructions, unsure: unsure, words: words) {
            ingredients.contains($0) ? $0 : nil
        }
        return PhotoReading(
            ingredients: split.ingredients,
            instructions: split.instructions,
            yield: split.yield,
            prepTime: split.prepTime,
            cookTime: split.cookTime,
            totalTime: split.totalTime,
            sorted: true,
            uncertain: checked.uncertain,
            large: checked.large,
            language: language
        )
    }

    /// The `shown` lines to check: those `marked` from text the recogniser was unsure of, then any
    /// other whose amount looks misread (`suspect`), however sure the recogniser was. The size
    /// check (`implausible`) was calibrated on ingredient lines, so it runs only on the text
    /// `sized` gives for a line, and not where it gives nil (a step); a line only it flags is
    /// also in `large` (#281).
    private static func toCheck(
        _ shown: [String], unsure: [String], words: LanguageWords, sized: (String) -> String?
    ) -> (uncertain: [String], large: [String]) {
        let low = Set(marked(shown, unsure: unsure))
        var uncertain: [String] = []
        var large: [String] = []
        for line in shown where !uncertain.contains(line) {
            let misread = low.contains(line) || misread(Lead(line, words: words), words: words)
            let big = !misread && (sized(line).map { implausible($0, words: words) } ?? false)
            if misread || big { uncertain.append(line) }
            if big { large.append(line) }
        }
        return (uncertain, large)
    }

    /// True when `line`'s amount has a shape a recogniser gives for a misread one (#198), so the
    /// cook checks it against the photo. The text is never changed:
    /// - an improper fraction over 2 to 8, "11/2" or "31/3": most likely "1 1/2" or "3 1/3" with
    ///   its space lost (and the scaler would read 5½);
    /// - a digit beside a letter that looks like one: "l/2", "O.5", "1/Z", "1O", "35o°F";
    /// - a unit glued to the word after it, where the scaler reads no unit: "1 cupraisins", in
    ///   `words`' language ("2 ELZucker", "1 tazaharina"); never in one written without spaces;
    /// - a fraction glyph read as something else, or run into a digit or another glyph ("3¾4",
    ///   "½⅓", never written) (#245, #276): see `fractionSlot`;
    /// - a near-miss of a unit where the unit goes, "1 oup sugar" or "2 tbps butter" (#276): the
    ///   language's splitter.json `unitLookAlikes`, a short list, so "oz", "g" or "ml" never trip;
    /// - an amount too big for its unit, "34 tsp salt" or "23 cups oats" (#249): see `implausible`,
    ///   which `sort` runs on ingredient lines only (#281).
    static func suspect(_ line: String, words: LanguageWords = .english) -> Bool {
        let lead = Lead(line, words: words)
        return misread(lead, words: words) || implausible(lead, words: words)
    }

    /// Every shape `suspect` looks for but the size check.
    private static func misread(_ lead: Lead, words: LanguageWords) -> Bool {
        let line = lead.line
        let improper = improperFraction.findAll(line).contains { m in
            // The numerator without leading zeros: two digits or more is always above 2 to 8.
            let n = String(m[1].drop { $0 == "0" })
            guard let d = Int(m[2]), (2...8).contains(d) else { return false }
            return n.count > 1 || (!n.isEmpty && Int(n)! > d)
        }
        if improper || lookAlike.containsMatch(in: line) || fractionSlot(line, words: words) != nil { return true }
        guard let rest = lead.rest else { return false }
        return gluedUnit(rest, lead, words: words) || RecipeTextSplitter.unitLookAlike(rest, words: words)
    }

    /// `line`'s leading amount as `IngredientScaler` reads it (`match`), and the text after it.
    private struct Lead {
        let line: String
        let p: IngredientScaler.Patterns
        let match: JMatch?
        let rest: String?

        init(_ line: String, words: LanguageWords) {
            self.line = line
            p = IngredientScaler.patterns(words)
            match = p.leading.find(line)
            rest = match.map { line.u16Substring(from: $0.end) }
        }
    }

    /// True when `line`'s leading amount is more of its unit than a recipe asks for
    /// (`shared/tables/plausible-amounts.json`, #249): a fraction glyph read as digits, "34 tsp
    /// salt" for ¾ or "23 cups oats" for 2 ⅔. The amount and unit are `IngredientScaler`'s
    /// leading ones; either end of a range counts ("10–14 tsp"). An amount written exactly as a
    /// fraction's digits ("12" for ½: the table's `fractionShapes`) is held to its unit's lower
    /// `fractionShapedMaximums` bar where there is one, so "12 tbsp vinegar" is flagged and "16
    /// tablespoons butter" isn't (#281).
    ///
    /// Unlike the scaler, which reads only the first number, a plain whole number then another
    /// before the unit ("2 23 cups oats", ML Kit's 2 ⅔; "1 12 cups" for 1½) is judged by the
    /// second alone; after a range, a fraction or a decimal, the second number isn't taken. Flagged
    /// only: nothing is suggested or changed. A bare count ("12 eggs"), a unit the table doesn't
    /// list, a temperature ("180 C") and a language whose amounts follow the name are never checked.
    static func implausible(_ line: String, words: LanguageWords = .english) -> Bool {
        implausible(Lead(line, words: words), words: words)
    }

    private static func implausible(_ lead: Lead, words: LanguageWords) -> Bool {
        let p = lead.p
        guard !p.amountAfterName, let match = lead.match, var rest = lead.rest else { return false }
        var amounts = [match[2], match[4]].filter { !$0.isEmpty }
        if amounts.count == 1, amounts[0].allSatisfy({ ("0"..."9").contains($0) }), let second = secondNumber.find(rest) {
            amounts = [second[1]]
            rest = rest.u16Substring(from: second.end)
        }
        guard let unit = p.unitAtStart.find(rest).flatMap({ MeasureUnit.fromText($0[1], words: words) }),
              !p.temperature(amounts, rest) else { return false }
        let table = plausible
        guard let max = table.maximums[unit] else { return false }
        let shapedMax = table.fractionShaped[unit] ?? max
        return amounts.contains { (p.parse($0) ?? 0) > (table.shapes.contains($0) ? shapedMax : max) }
    }

    /// Read at the first size check (a static is lazy), and a malformed table traps, as
    /// `SharedTables` does.
    private static let plausible: PlausibleAmounts = {
        guard let table = PlausibleAmounts(SharedTables.read("plausible-amounts")) else {
            fatalError("Malformed shared table tables/plausible-amounts.json")
        }
        return table
    }()

    // "2 23 cups": a second whole number straight after the leading one.
    private static let secondNumber = JRegex(#"^\s+(\d+)(?![\d/⁄.,])"#)

    /// The fractions offered to replace a look-alike (#245), in the order shown.
    static var fractions: [String] { FractionLookAlike.fractions }

    /// What a fraction chip replaces in `line` (#245, #276): its amount's look-alike
    /// (`FractionLookAlike.slot`), else a glyph run into a digit or another glyph
    /// (`FractionLookAlike.gluedRun`). Nil if neither.
    static func fractionSlot(_ line: String, words: LanguageWords = .english) -> NSRange? {
        FractionLookAlike.slot(line, unitAtStart: IngredientScaler.patterns(words).unitAtStart)
            ?? FractionLookAlike.gluedRun(line)
    }

    /// `line` with its `fractionSlot` replaced by `fraction`, the cook's choice; nil if it has none.
    /// A whole number glued before a glyph run is kept and set apart ("1½⅓ cup" → "1 ⅓ cup", #276).
    static func replaceFraction(_ line: String, _ fraction: String, words: LanguageWords = .english) -> String? {
        let ns = line as NSString
        if let slot = FractionLookAlike.slot(line, unitAtStart: IngredientScaler.patterns(words).unitAtStart) {
            return ns.replacingCharacters(in: slot, with: fraction)
        }
        guard let run = FractionLookAlike.gluedRun(line) else { return nil }
        let apart = run.location > 0 && (48...57).contains(ns.character(at: run.location - 1))
        return ns.replacingCharacters(in: run, with: apart ? " " + fraction : fraction)
    }

    /// A unit glued to the next word in `rest`, after the leading amount, where the scaler reads none.
    private static func gluedUnit(_ rest: String, _ lead: Lead, words: LanguageWords) -> Bool {
        words.spaced && !lead.p.unitAtStart.containsMatch(in: rest) && RecipeTextSplitter.gluedUnit(rest, words: words)
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

/// `shared/tables/plausible-amounts.json` (#249, #281), read. Nil for a malformed table (no
/// `maximums`, a unit that isn't a `MeasureUnit`, a value that isn't a number, a fraction-shaped
/// bar not below its unit's maximum), which `PhotoTextSorter` traps on, as `SharedTables` does.
/// Android's `PlausibleAmounts` throws.
struct PlausibleAmounts {
    let maximums: [MeasureUnit: Double]
    let fractionShaped: [MeasureUnit: Double]
    let shapes: Set<String>

    init?(_ table: SharedTables.Table) {
        func units(_ key: String) -> [MeasureUnit: Double]? {
            guard let entries = table[key] as? [String: Any] else { return nil }
            var out: [MeasureUnit: Double] = [:]
            for (name, value) in entries {
                guard let unit = MeasureUnit.byTableName[name], let number = value as? NSNumber else { return nil }
                out[unit] = number.doubleValue
            }
            return out
        }
        guard let maximums = units("maximums"), let shaped = units("fractionShapedMaximums"),
              shaped.allSatisfy({ unit, bar in bar < (maximums[unit] ?? 0) }),
              let shapes = table["fractionShapes"] as? [String],
              shapes.allSatisfy({ !$0.isEmpty && $0.allSatisfy { ("0"..."9").contains($0) } })
        else { return nil }
        self.maximums = maximums
        fractionShaped = shaped
        self.shapes = Set(shapes)
    }
}
