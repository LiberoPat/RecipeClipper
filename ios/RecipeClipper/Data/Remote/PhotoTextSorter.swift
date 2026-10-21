import Foundation

/// One line of text the device read from a photo (#198), in reading order, with the
/// recogniser's `confidence` (0 to 1) when it gives one. `page` is the picture it was read from
/// (0 for the first) and `box` where on it (#286), so the review can show the line beside its
/// strip of the photo; nil when the reader didn't say. Android's `PhotoLine`.
struct PhotoLine: Equatable, Sendable {
    var text: String
    var confidence: Float? = nil
    var page: Int? = nil
    var box: PhotoBox? = nil
}

/// Where a line was read on its picture (#286): a rectangle in fractions of the picture's width
/// and height, 0 to 1, measured from its top-left corner, in the picture the right way up (as the
/// recogniser saw it and the review shows it). Android's `PhotoBox`.
struct PhotoBox: Equatable, Hashable, Sendable {
    let left: Float
    let top: Float
    let right: Float
    let bottom: Float
}

/// Why a line read from a photo is listed for the cook to check (#198). Flagged only: nothing is
/// corrected or suggested. Android's `CheckReason`.
enum CheckReason: Sendable {
    /// The recogniser was unsure of the line (`PhotoTextSorter.lowConfidence`).
    case unsure
    /// Its amount is shaped like a misreading ("11/2", "l/2", "1 cupraisins", "1 oup sugar").
    case misread
    /// Its amount holds a fraction glyph's look-alike, or glyphs run together, which the fraction
    /// chips replace (#245, #276): always with `misread`.
    case fraction
    /// Its amount is more of its unit than a recipe asks for ("34 tsp salt", #249, #281).
    case large
}

/// One line listed to check (#198, #283): `raw` as it is in the editor's boxes, which a fraction
/// chip changes; `shown` as the list shows it, without list markup ("▢", "•", "3)"); and why.
/// `page` and `box` are where it was read (#286: its `PhotoLine`'s), nil when the reader didn't say.
struct LineFlag: Equatable, Sendable {
    var raw: String
    var shown: String
    var reasons: Set<CheckReason>
    var page: Int? = nil
    var box: PhotoBox? = nil

    /// Listed only because its amount looks too large: the photo was read clearly there, so it
    /// gets a note of its own rather than the heading (#281).
    var largeOnly: Bool { reasons == [.large] }

    /// Its amount holds a look-alike the fraction chips replace (#245).
    var chips: Bool { reasons.contains(.fraction) }
}

/// What `PhotoTextSorter.sort` made of a photo's lines: the recipe editor's content, before the
/// cook checks it. When `sorted` is false the splitter found no recipe, and `ingredients` holds
/// every line read, as read, for the cook to sort by hand.
struct PhotoReading: Equatable, Sendable {
    let ingredients: [String]
    let instructions: [String]
    var yield: String? = nil
    var prepTime: String? = nil
    var cookTime: String? = nil
    var totalTime: String? = nil
    let sorted: Bool
    /// The lines to check, each once, in the order shown, with why (`PhotoTextSorter.check`).
    var flags: [LineFlag] = []
    /// The language the lines' words clearly say (#208), which they were read in; nil when nothing
    /// was clear and English was used.
    var language: String? = nil

    /// Nothing at all was read.
    var isEmpty: Bool { ingredients.isEmpty && instructions.isEmpty }

    /// The `flags`' lines, as they are in the boxes.
    var uncertain: [String] { flags.map(\.raw) }

    /// The `flags`' lines listed only because their amount looks too large (`LineFlag.largeOnly`).
    var large: [String] { flags.filter(\.largeOnly).map(\.raw) }
}

/// Sorts the lines read from a photo (#198) with the same splitter that reads a typed Reddit
/// post (`RecipeTextSplitter`), so headers, lists and numbered steps are recognised the same way.
///
/// **Nothing is invented or corrected.** Every line is the recogniser's text (only trimmed, and
/// cleaned by the splitter as a typed post's lines are); an amount it misread stays misread, and
/// the lines to check are listed in `flags` with why: the recogniser was unsure of the line, or its
/// amount is shaped like a misreading or looks too large (`check`). When the splitter finds no
/// recipe the lines are returned unsorted, never guessed at.
///
/// The lines are read in the language their words say (#208, detected as a page's is, #14), else
/// English: that language's headers, and its unit words for `check`.
///
/// Pure: lines in, data out. A line-for-line port of Android's `PhotoTextSorter.kt`.
enum PhotoTextSorter {
    /// Below this, a line is marked "check this". Vision's accurate recogniser answers 0.3 for
    /// text it is unsure of and 0.5 or 1 otherwise; ML Kit's scores fall on the same scale.
    static let lowConfidence: Float = 0.5

    static func sort(_ lines: [PhotoLine]) -> PhotoReading {
        // A recogniser's line holds no break, but a caller's may: split as the splitter's lines are.
        // A piece of a line keeps the whole line's confidence, page and box (#286).
        let read = lines.flatMap { line in
            line.text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
                .components(separatedBy: "\n").map { piece -> PhotoLine in
                    var copy = line
                    copy.text = piece.kTrimmed
                    return copy
                }
        }.filter { !$0.text.isEmpty }
        let text = read.map(\.text).joined(separator: "\n")
        let language = RecipeTextSplitter.languageOf(text)
        let words = RecipeTextSplitter.wordsFor(language)
        // Each line cleaned once, as the splitter reads it: the sorting, the checks and the list
        // all use it (#283). A line read again (a card's two photos) is cleaned once.
        var clean: [String: RecipeTextSplitter.Line] = [:]
        let cleaned = read.map { line -> RecipeTextSplitter.Line in
            if let known = clean[line.text] { return known }
            let made = RecipeTextSplitter.line(line.text, words: words)
            clean[line.text] = made
            return made
        }
        let split = read.isEmpty ? nil : RecipeTextSplitter.splitLines(cleaned, words: words)
        var listed = Listed(read: read, cleaned: cleaned, words: words)
        guard let split, !(split.ingredients.isEmpty && split.instructions.isEmpty) else {
            // Unsorted, each line is checked and shown as the splitter cleans it ("▢ 2% cups oats"
            // as "2% cups oats"), and stays in the box as read.
            for i in read.indices { listed.add(read[i].text, cleaned[i].text, source: i) }
            return PhotoReading(ingredients: read.map(\.text), instructions: [], sorted: false, flags: listed.flags(), language: language)
        }
        for kept in split.ingredients + split.instructions { listed.add(kept.text, kept.text, source: kept.source) }
        let recipe = split.recipe
        return PhotoReading(
            ingredients: recipe.ingredients,
            instructions: recipe.instructions,
            yield: recipe.yield,
            prepTime: recipe.prepTime,
            cookTime: recipe.cookTime,
            totalTime: recipe.totalTime,
            sorted: true,
            flags: listed.flags(),
            language: language
        )
    }

    /// The lines to list, gathered line by line: each box line (`LineFlag.raw`) once, in the order
    /// first shown, with the reasons of every copy of it, and the page and box of the first (#286).
    /// The splitter keeps each read line whole (it never joins two), so an ingredient or step was
    /// read where its own line was.
    private struct Listed {
        let read: [PhotoLine]
        let cleaned: [RecipeTextSplitter.Line]
        let words: LanguageWords
        private var order: [String] = []
        private var shown: [String: String] = [:]
        private var reasons: [String: Set<CheckReason>] = [:]
        private var checked: [Checked: Set<CheckReason>] = [:]
        private var at: [String: PhotoLine] = [:]

        private struct Checked: Hashable {
            let text: String
            let labelled: Bool
        }

        init(read: [PhotoLine], cleaned: [RecipeTextSplitter.Line], words: LanguageWords) {
            self.read = read
            self.cleaned = cleaned
            self.words = words
        }

        /// `raw`, a box's line, from read line `source`, checked and shown as `text`. Its confidence
        /// and "Step 3" label are its own line's, never another's that quotes it.
        mutating func add(_ raw: String, _ text: String, source: Int) {
            let show = text.isEmpty ? raw : text
            let labelled = cleaned[source].step
            // A line read again (a card's two photos) is checked once.
            let key = Checked(text: text, labelled: labelled)
            var found: Set<CheckReason>
            if let known = checked[key] {
                found = known
            } else {
                found = PhotoTextSorter.check(text, words: words, labelled: labelled)
                checked[key] = found
            }
            // A chip replaces the look-alike in the line as shown, so the box's line must hold it.
            if (raw as NSString).range(of: show, options: .literal).location == NSNotFound { found.remove(.fraction) }
            if (read[source].confidence ?? 1) < PhotoTextSorter.lowConfidence { found.insert(.unsure) }
            if shown[raw] == nil {
                shown[raw] = show
                order.append(raw)
                at[raw] = read[source]
            }
            reasons[raw, default: []].formUnion(found)
        }

        func flags() -> [LineFlag] {
            order.compactMap { raw in
                guard let found = reasons[raw], !found.isEmpty, let show = shown[raw] else { return nil }
                return LineFlag(raw: raw, shown: show, reasons: found, page: at[raw]?.page, box: at[raw]?.box)
            }
        }
    }

    /// Why `line`, as the splitter cleaned it, is to be checked against the photo however sure the
    /// recogniser was (#198); empty if it isn't. The text is never changed. `misread`:
    /// - an improper fraction over 2 to 8, "11/2" or "31/3": most likely "1 1/2" or "3 1/3" with
    ///   its space lost (and the scaler would read 5½);
    /// - a digit beside a letter that looks like one: "l/2", "O.5", "1/Z", "1O", "35o°F";
    /// - a unit glued to the word after it, where the scaler reads no unit: "1 cupraisins", in
    ///   `words`' language ("2 ELZucker", "1 tazaharina"); never in one written without spaces;
    /// - a fraction glyph read as something else, or run into a digit or another glyph ("3¾4",
    ///   "½⅓", never written) (#245, #276): see `fractionSlot`, which is also `fraction`, the chips;
    /// - a near-miss of a unit where the unit goes, "1 oup sugar" or "2 tbps butter" (#276): the
    ///   language's splitter.json `unitLookAlikes`, a short list, so "oz", "g" or "ml" never trip.
    ///
    /// `large`: an amount too big for its unit, "34 tsp salt" or "23 cups oats" (#249), judged by
    /// `implausible` only on a line that starts with an amount and its unit, as an ingredient line
    /// does. So it runs on the steps too, where a section after the method lands ("34 tsp vanilla"
    /// under a glaze, #283), and a sentence ("Stir in 34 tsp salt.") is never judged; never on a
    /// line that had a "Step 3" label (`labelled`) or that holds "=" (a conversion, "48 tsp = 1 cup").
    static func check(_ line: String, words: LanguageWords = .english, labelled: Bool = false) -> Set<CheckReason> {
        let lead = Lead(line, words: words)
        let chips = fractionSlot(line, words: words) != nil
        var reasons: Set<CheckReason> = []
        if chips || misread(lead, words: words) { reasons.insert(.misread) }
        if chips { reasons.insert(.fraction) }
        if !labelled, !line.contains("="), implausible(lead, words: words) { reasons.insert(.large) }
        return reasons
    }

    /// Every misread shape `check` looks for but the fraction look-alikes (`fractionSlot`).
    private static func misread(_ lead: Lead, words: LanguageWords) -> Bool {
        let line = lead.line
        let improper = improperFraction.findAll(line).contains { m in
            // The numerator without leading zeros: two digits or more is always above 2 to 8.
            let n = String(m[1].drop { $0 == "0" })
            guard let d = Int(m[2]), (2...8).contains(d) else { return false }
            return n.count > 1 || (!n.isEmpty && Int(n)! > d)
        }
        if improper || lookAlike.containsMatch(in: line) { return true }
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
    /// leading ones, so only a line that starts with an amount and its unit is judged. An amount
    /// written exactly as a fraction's digits ("12" for ½: the table's `fractionShapes`) is held
    /// to its unit's lower `fractionShapedMaximums` bar where there is one, so "12 tbsp vinegar"
    /// is flagged and "16 tablespoons butter" isn't (#281). Either end of a range is held to the
    /// unit's own maximum, never that lower bar ("12–14 lb turkey" is fine, "30–40 lb" isn't: the
    /// owner's call, #283).
    ///
    /// Unlike the scaler, which reads only the first number, a plain whole number then another
    /// before the unit ("2 23 cups oats", ML Kit's 2 ⅔; "1 12 cups" for 1½) is judged by the
    /// second alone; after a range, a fraction or a decimal, the second number isn't taken. Flagged
    /// only: nothing is suggested or changed. A bare count ("12 eggs"), a unit the table doesn't
    /// list, a temperature ("180 C"; never a fraction's digits, "1 58 C sugar" is 1 ⅝ cups, #283)
    /// and a language whose amounts follow the name are never checked.
    static func implausible(_ line: String, words: LanguageWords = .english) -> Bool {
        implausible(Lead(line, words: words), words: words)
    }

    private static func implausible(_ lead: Lead, words: LanguageWords) -> Bool {
        let p = lead.p
        guard !p.amountAfterName, let match = lead.match, var rest = lead.rest else { return false }
        var amounts = [match[2], match[4]].filter { !$0.isEmpty }
        let range = amounts.count > 1
        if amounts.count == 1, amounts[0].allSatisfy({ ("0"..."9").contains($0) }), let second = secondNumber.find(rest) {
            amounts = [second[1]]
            rest = rest.u16Substring(from: second.end)
        }
        guard let unit = p.unitAtStart.find(rest).flatMap({ MeasureUnit.fromText($0[1], words: words) }) else { return false }
        let table = plausible
        if !amounts.contains(where: { table.shapes.contains($0) }), p.temperature(amounts, rest) { return false }
        guard let max = table.maximums[unit] else { return false }
        let shapedMax = range ? max : table.fractionShaped[unit] ?? max
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

    /// `flag` once the cook taps `fraction` (#245): the look-alike replaced in the line as shown,
    /// and so in the box's line, which holds it ("▢ 2% cups oats" → "▢ 2⅔ cups oats"); its chips
    /// go, and it stays listed. Nil if it has no chips.
    static func replaceFraction(_ flag: LineFlag, _ fraction: String, words: LanguageWords = .english) -> LineFlag? {
        guard flag.chips, let shown = replaceFraction(flag.shown, fraction, words: words) else { return nil }
        let raw = flag.raw as NSString
        let at = raw.range(of: flag.shown, options: .literal)
        guard at.location != NSNotFound else { return nil }
        return LineFlag(
            raw: raw.replacingCharacters(in: at, with: shown), shown: shown, reasons: flag.reasons.subtracting([.fraction]),
            page: flag.page, box: flag.box
        )
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
}

/// `shared/tables/plausible-amounts.json` (#249, #281), read strictly, the same as Android
/// (#283): every maximum a JSON number (not a string, not true or false), finite and above zero,
/// keyed by a `MeasureUnit`; each fraction-shaped bar below its unit's maximum; every fraction
/// shape a JSON string of digits. Nil for anything else, which `PhotoTextSorter` traps on, as
/// `SharedTables` does; Android's `PlausibleAmounts` throws.
struct PlausibleAmounts {
    let maximums: [MeasureUnit: Double]
    let fractionShaped: [MeasureUnit: Double]
    let shapes: Set<String>

    init?(_ table: SharedTables.Table) {
        func units(_ key: String) -> [MeasureUnit: Double]? {
            guard let entries = table[key] as? [String: Any] else { return nil }
            var out: [MeasureUnit: Double] = [:]
            for (name, value) in entries {
                // JSONSerialization gives true and false as NSNumbers too: only a JSON number counts.
                guard let unit = MeasureUnit.byTableName[name], let number = value as? NSNumber,
                      CFGetTypeID(number) != CFBooleanGetTypeID(),
                      number.doubleValue.isFinite, number.doubleValue > 0
                else { return nil }
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
