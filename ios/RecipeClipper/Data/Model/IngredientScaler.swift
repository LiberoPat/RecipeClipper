import Foundation

/// Scales the leading quantity of an ingredient line ("1 1/2 cups flour",
/// "½ tsp salt", "1-2 tbsp oil"). Pure: string in, string out.
///
/// The leading quantity is scaled, with the measures that restate it: "(120 g)" after the unit,
/// a second part ("plus 2 tbsp", "minus 2 tbsp"), an alternative ("or 1/2 cup oil", #61), a part
/// added later ("plus 3 egg yolks", #62) and a total in brackets after the name ("(8 ½ ounces)",
/// #63). A line that doesn't start with a number ("salt to taste"), or whose number is a
/// measurement rather than an amount ("1-inch piece ginger", "2% milk"), is returned unchanged,
/// and so is one where any of those can't be scaled with the rest. Wrong scaling is worse than
/// no scaling, so anything ambiguous is left alone.
enum IngredientScaler {

    private static let unicodeFractions = "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"

    private static let unicodeValues: [Character: Double] = [
        "¼": 1 / 4.0, "½": 1 / 2.0, "¾": 3 / 4.0, "⅐": 1 / 7.0, "⅑": 1 / 9.0,
        "⅒": 1 / 10.0, "⅓": 1 / 3.0, "⅔": 2 / 3.0, "⅕": 1 / 5.0, "⅖": 2 / 5.0,
        "⅗": 3 / 5.0, "⅘": 4 / 5.0, "⅙": 1 / 6.0, "⅚": 5 / 6.0, "⅛": 1 / 8.0,
        "⅜": 3 / 8.0, "⅝": 5 / 8.0, "⅞": 7 / 8.0,
    ]

    // "1 1/2", "2 and 1/2", "1 and ½", "1½", "1/2", "1.5", "1,5", "½", "2" — tried in that
    // order. The "and" is the language's (amounts.json "mixedJoiners"). A fraction's slash may
    // be the typographic U+2044 ("1⁄2", as BBC Good Food writes it), a symbol rather than a
    // word, so it stays here. A comma followed by one or two digits is a decimal comma; one
    // followed by three ("1,500") may be a thousands separator, so it is never read as part
    // of a quantity, and `ambiguousComma` leaves the line. Where the language writes thousands
    // with a dot (amounts.json "thousandsDot"), "1.500" is 1500 (#76): only a dot before
    // exactly three digits, so "1.5" and "0.25" stay decimals.
    private static func qtyPattern(_ joiner: String, thousandsDot: Bool) -> String {
        #"(?:\d+\s+(?:"# + joiner + #"\s+)?\d+[/⁄]\d+|\d+\s+"# + joiner + #"\s+["# + unicodeFractions + #"]|\d+\s*["# +
            unicodeFractions + #"]|\d+[/⁄]\d+|"# + (thousandsDot ? #"\d{1,3}\.\d{3}(?![\d.,])|"# : "") +
            #"\d+(?:\.\d+|,\d{1,2}(?!\d))?|["# + unicodeFractions + #"])"#
    }

    /// "1,5": the line writes decimals with a comma, so its output does too.
    static let decimalComma = JRegex(#"\d,\d{1,2}(?!\d)"#)

    /// "1,500" is 1.5 or 1500 depending on who wrote it: a line holding one is left as written.
    static let ambiguousComma = JRegex(#"\d,\d{3}"#)

    /// Where dots mark thousands, "1.500,5" or "1.2345" is no amount the pattern reads whole.
    private static let ambiguousDot = JRegex(#"\d\.\d{3}[\d.,]"#)

    private static let thousandsDotSeparator = JRegex(#"(?<=\d)\.(?=\d{3}(?!\d))"#)

    private static let decimalPoint = JRegex(#"(?<=\d)\.(?=\d)"#)

    /// "2.25" as "2,25" when `comma`, for a line that writes its decimals that way.
    static func withSeparator(_ text: String, comma: Bool) -> String {
        comma ? decimalPoint.replace(text, with: ",") : text
    }

    /// The patterns that read one language's words (shared/tables/<language>/amounts.json).
    final class Patterns {
        let words: LanguageWords

        /// "1.500 g" is 1500 g (amounts.json "thousandsDot").
        let thousandsDot: Bool

        /// Lines are "name amount" ("醤油 大さじ1"), read by `TrailingAmount` (amounts.json "amountAfterName").
        let amountAfterName: Bool

        /// A quantity, in this language's words ("2 and 1/2"). No capturing group.
        let qty: String

        // "1 taza y media", "2 e meia": a half in words, which the quantity pattern can't read.
        private let spelledHalf: JRegex

        // groups: 1 leading space, 2 quantity, 3 range separator, 4 range upper bound
        let leading: JRegex

        // What follows the number when it is a size or a percentage, not an amount.
        let notAnAmount: JRegex

        // A quantity followed by a unit. groups: 1 quantity, 2 space, 3 unit
        let qtyUnit: JRegex

        // "1 cup (120 g) flour": a second measure of the same amount, right after a unit word.
        // A parenthesis straight after the number ("1 (14 oz) can") or after a container word
        // ("1 can (14 oz)") is a package size, not an alternate measure, and is never scaled.
        let altParen: JRegex

        // "1 cup/120 grams flour", or a range "250 - 300 g / 8 - 10 oz pasta".
        // groups: 1 prefix, 2 quantity, 3 range separator, 4 range upper bound, 5 space, 6 unit
        let altSlash: JRegex

        // The word joining the two parts of a compound amount: "1 cup plus 2 tbsp", "1 cup + 2 tbsp".
        let continuation: String

        /// A second part taken away rather than added: "2 cups minus 2 tbsp" (#62).
        let subtraction: String

        // "1 cup plus 2 tbsp (140 g) flour": the second part of a compound amount, which scales
        // with the first. groups: 1 prefix, 2 quantity, 3 space, 4 unit
        let continued: JRegex

        /// The amount is a measure (it has a unit), not a count: "2 cups", "¾ de taza".
        let unitAtStart: JRegex

        // A second amount later in the line (#61, #62). Group 1 holds an alternative's word
        // ("or 1/2 cup oil"), an amount standing for the whole; otherwise the amount is a part
        // added or taken away ("plus 3 egg yolks", "+ 1 egg yolk", "minus 2 tbsp").
        let joined: JRegex

        // An amount in a bracket: a quantity or range and its unit.
        // groups: 1 quantity, 2 range separator, 3 upper bound, 4 space, 5 unit
        let measure: JRegex

        // "(about 1/4 cup)", "(200ml/7fl oz)", "(ca. 800g)", "(180 g.)": a bracket holding nothing
        // but an amount, perhaps written two ways, perhaps "about" it (#63).
        let total: JRegex

        /// "(8 oz each)": a per-item size.
        let perItem: JRegex

        /// "1 can (14 oz)": a bracket straight after a container word is the container's size.
        let afterContainer: JRegex

        /// "1 lata leite condensado (397 g)": one container, so its bracket is the container's size.
        let containerFirst: JRegex

        init(_ words: LanguageWords) {
            self.words = words
            let thousandsDot = words.table("amounts")["thousandsDot"] as? Bool ?? false
            self.thousandsDot = thousandsDot
            amountAfterName = words.table("amounts")["amountAfterName"] as? Bool ?? false
            let qty = IngredientScaler.qtyPattern(
                SharedTables.alternation(words.strings("amounts", "mixedJoiners")), thousandsDot: thousandsDot
            )
            self.qty = qty
            spelledHalf = JRegex(
                #"(?<!\p{L})"# + SharedTables.alternation(words.strings("amounts", "spelledHalves")) + #"(?!\p{L})"#,
                ignoreCase: true
            )
            let units = UnitPatterns.of(words)
            leading = JRegex(#"^(\s*)("# + qty + #")(?:(\s*[-–—]\s*|\s+"# + words.rangeWords + #"\s+)("# + qty + #"))?"#)
            notAnAmount = JRegex(
                #"^\s*-?\s*(?:"# + (["%"] + words.strings("amounts", "sizes") + [#"cm\b"#, #"mm\b"#]).joined(separator: "|") + ")",
                ignoreCase: true
            )
            qtyUnit = JRegex("(" + qty + #")(\s*)"# + units.captured, ignoreCase: true)
            altParen = JRegex(#"^(\s*"# + units.plain + #"\s*\()([^)]*)(\))"#, ignoreCase: true)
            altSlash = JRegex(
                #"^(\s*"# + units.plain + #"\s*/\s*)("# + qty + #")(?:(\s*[-–—]\s*|\s+"# + words.rangeWords + #"\s+)("# +
                    qty + #"))?(\s*)"# + units.captured,
                ignoreCase: true
            )
            continuation = "(?:" + (words.strings("amounts", "continuation") + [#"\+\s*"#]).joined(separator: "|") + ")"
            let subtraction = SharedTables.alternation(words.strings("amounts", "subtractions"))
            self.subtraction = subtraction
            continued = JRegex(
                #"^(\s*"# + units.plain + #"\s*(?:"# + continuation + "|" + subtraction + #"))("# + qty + #")(\s*)"# +
                    units.captured,
                ignoreCase: true
            )
            unitAtStart = JRegex(
                // Wrapped, since ICU rejects a quantifier straight after "(?!)" (no prefixes).
                #"^\s*(?:"# + SharedTables.alternation(words.strings("amounts", "unitPrefixes")) + ")?" + units.plain,
                ignoreCase: true
            )
            joined = JRegex(
                #"(?:(?<!\p{L})("# + SharedTables.alternation(words.strings("amounts", "alternatives")) + ")|" +
                    #"(?<!\p{L})"# + SharedTables.alternation(words.strings("amounts", "additions")) + "|" +
                    #"(?<!\p{L})"# + subtraction + #"|\+\s*)(?="# + qty + ")",
                ignoreCase: true
            )
            let rangeSeparator = #"\s*[-–—]\s*|\s+"# + words.rangeWords + #"\s+"#
            measure = JRegex(
                "(" + qty + ")(?:(" + rangeSeparator + ")(" + qty + #"))?(\s*)"# + units.captured, ignoreCase: true
            )
            let plainMeasure = "(?:" + qty + ")(?:(?:" + rangeSeparator + ")(?:" + qty + #"))?\s*"# + units.plain
            total = JRegex(
                #"^\s*(?:"# + SharedTables.alternation(words.strings("amounts", "approximately")) + #"\s*)?(?:[~≈]\s*)?"# +
                    plainMeasure + #"(?:\s*/\s*"# + plainMeasure + #")?\s*\.?\s*$"#,
                ignoreCase: true
            )
            perItem = JRegex(
                #"(?<!\p{L})"# + SharedTables.alternation(words.strings("amounts", "perItem")) + #"(?!\p{L})"#,
                ignoreCase: true
            )
            let containers = SharedTables.alternation(words.strings("amounts", "containers"))
            afterContainer = JRegex(#"(?<!\p{L})"# + containers + #"\s*$"#, ignoreCase: true)
            containerFirst = JRegex(#"^\s*"# + containers + #"(?!\p{L})"#, ignoreCase: true)
        }

        /// A line whose amount can't be read without guessing: "1,500" (1.5 or 1500?), a half in
        /// words, or where dots mark thousands a number like "1.500,5". It stays as written.
        func unreadable(_ line: String) -> Bool {
            IngredientScaler.ambiguousComma.containsMatch(in: line) || spelledHalf.containsMatch(in: line) ||
                (thousandsDot && IngredientScaler.ambiguousDot.containsMatch(in: line))
        }

        /// A quantity this pattern matched, read with this language's thousands separator.
        func parse(_ quantity: String) -> Double? { IngredientScaler.parse(quantity, thousandsDot: thousandsDot) }
    }

    static func patterns(_ words: LanguageWords) -> Patterns { words.compiled(Patterns.self, Patterns.init) }

    // Nearest-fraction table used when formatting; anything further than `tolerance`
    // from all of these falls back to a plain decimal.
    private static let fractions: [(value: Double, text: String)] = [
        (0.0, ""), (1 / 8.0, "1/8"), (1 / 4.0, "1/4"), (1 / 3.0, "1/3"), (3 / 8.0, "3/8"),
        (1 / 2.0, "1/2"), (5 / 8.0, "5/8"), (2 / 3.0, "2/3"), (3 / 4.0, "3/4"),
        (7 / 8.0, "7/8"), (1.0, ""),
    ]
    private static let tolerance = 0.02

    private static let whitespace = JRegex(#"\s+"#)

    /// `words` nil: a language the app has no words for, so the line stays as written.
    static func scale(_ line: String, factor: Double, words: LanguageWords? = .english) -> String {
        guard factor != 1.0, let words else { return line }
        let p = patterns(words)
        if p.amountAfterName { return TrailingAmount.scale(line, factor: factor, words: words) }
        if p.unreadable(line) { return line }
        let comma = decimalComma.containsMatch(in: line)
        guard let sides = walk(p, line, factor: factor, comma: comma) else { return line }
        return sides.map(\.text).joined()
    }

    /// One amount of a line and the text it governs, up to the next amount's joining word:
    /// "1 cup butter " and "1/2 cup oil" in "1 cup butter or 1/2 cup oil". `text` is the side
    /// scaled, followed by the joining word as written (`end` is where that word starts).
    private struct Side {
        let start: Int
        let end: Int
        let text: String
    }

    /// Where each amount of `line` starts and ends (UTF-16 offsets): the first, then any after an
    /// alternative's or a second part's word ("or 1/2 cup oil", "plus 3 egg yolks"). Nil when the
    /// line can't be scaled whole, so the converter reads it as one amount, as before #61.
    static func sides(_ p: Patterns, _ line: String) -> [Range<Int>]? {
        walk(p, line, factor: 1.0, comma: false)?.map { $0.start..<$0.end }
    }

    // Scales every amount of the line, or none: nil leaves the whole line as written.
    private static func walk(_ p: Patterns, _ line: String, factor: Double, comma: Bool) -> [Side]? {
        var sides: [Side] = []
        var start = 0
        var alternative = false
        while true {
            let text = line.u16Substring(from: start)
            guard let match = p.leading.find(text) else { return nil }
            let rest = text.u16Substring(from: match.end)
            if p.notAnAmount.containsMatch(in: rest) { return nil }
            let measure = p.unitAtStart.containsMatch(in: rest)
            // "or 2 small onions": an alternative needs a unit to be read as one (#61).
            if alternative && !measure { return nil }

            guard let low = p.parse(match[2]) else { return nil }
            let upperRaw = match[4]
            let scaled: String
            if upperRaw.isEmpty {
                scaled = formatLeading(low * factor, comma: comma)
            } else {
                guard let high = p.parse(upperRaw) else { return nil }
                scaled = formatLeading(low * factor, comma: comma) + match[3] + formatLeading(high * factor, comma: comma)
            }
            let (region, regionLength) = scaleRegion(p, rest, factor: factor, comma: comma)
            let tailStart = start + match.end + regionLength
            let next = nextAmount(p, line, from: tailStart)
            let end = next?.start ?? line.u16Count
            let one = upperRaw.isEmpty && low == 1.0
            guard let tail = scaleBrackets(
                p, line.u16Substring(tailStart, end), factor: factor, comma: comma, measure: measure, one: one
            ) else { return nil }
            sides.append(Side(start: start, end: end, text: match[1] + scaled + region + tail + (next?.value ?? "")))
            guard let next else { return sides }
            start = next.end
            alternative = !next[1].isEmpty
        }
    }

    // The next joining word followed by an amount, outside brackets or opening one:
    // "or 2 cups" and "(or 1/2 cup oil)" count, the "or" in "(14 oz or 400 g)" doesn't.
    private static func nextAmount(_ p: Patterns, _ line: String, from: Int) -> JMatch? {
        var match = p.joined.find(line, from: from)
        while let m = match {
            let before = line.u16Substring(from, m.start)
            if depth(before) == 0 || trimEnd(before).hasSuffix("(") { return m }
            match = p.joined.find(line, from: m.end)
        }
        return nil
    }

    private static func depth(_ text: String) -> Int {
        var depth = 0
        for c in text.utf16 {
            if c == 0x28 { depth += 1 } else if c == 0x29 && depth > 0 { depth -= 1 }
        }
        return depth
    }

    /// Kotlin's `trimEnd()`.
    private static func trimEnd(_ text: String) -> String {
        var s = Substring(text)
        while let last = s.last, last.isWhitespace { s = s.dropLast() }
        return String(s)
    }

    // A line that writes "1,5" reads decimals, not fractions: "1,5 kg" x 1.5 is "2,25 kg".
    private static func formatLeading(_ value: Double, comma: Bool) -> String {
        comma ? withSeparator(plainDecimal(value, scale: 2), comma: true) : format(value)
    }

    /// Scales "plus 2 tbsp" and whatever alternate measure follows it, else just the alternate.
    /// Returns the scaled text and how much of `rest` (UTF-16 units) it stands for.
    private static func scaleRegion(_ p: Patterns, _ rest: String, factor: Double, comma: Bool) -> (String, Int) {
        guard let m = p.continued.find(rest),
              let unit = MeasureUnit.fromText(m[4], words: p.words),
              let value = p.parse(m[2]) else { return scaleAlternateMeasure(p, rest, factor: factor, comma: comma) }
        // The alternate measure is read from the second part's unit on.
        let unitText = m[3] + m[4]
        let (alternate, length) = scaleAlternateMeasure(
            p, unitText + rest.u16Substring(from: m.end), factor: factor, comma: comma
        )
        return (m[1] + formatFor(unit, value * factor, comma: comma) + alternate, m.end - unitText.u16Count + length)
    }

    /// Keeps "(120 g)" or "/120 grams" in step with the leading amount that was just scaled.
    /// Returns the scaled text and how much of `rest` it stands for (none when there is none).
    private static func scaleAlternateMeasure(_ p: Patterns, _ rest: String, factor: Double, comma: Bool) -> (String, Int) {
        if let m = p.altParen.find(rest) {
            let inner = p.qtyUnit.replace(m[2]) { scalePair(p, $0, factor: factor, comma: comma) }
            return (m[1] + inner + m[3], m.end)
        }
        if let m = p.altSlash.find(rest),
           let unit = MeasureUnit.fromText(m[6], words: p.words),
           let value = p.parse(m[2]) {
            let upper = m[4]
            let high = upper.isEmpty ? nil : p.parse(upper)
            if upper.isEmpty || high != nil {
                let range = high.map { m[3] + formatFor(unit, $0 * factor, comma: comma) } ?? ""
                return (m[1] + formatFor(unit, value * factor, comma: comma) + range + m[5] + m[6], m.end)
            }
        }
        return ("", 0)
    }

    /// A bracket in a side's text (UTF-16 offsets): `start` and `end` include the brackets, the
    /// content is between.
    struct Bracket {
        let start: Int
        let end: Int
        let contentStart: Int
        let contentEnd: Int
    }

    /// Brackets at the outer level, nested ones inside them; an unclosed one runs to the end.
    static func brackets(_ text: String) -> [Bracket] {
        var found: [Bracket] = []
        var depth = 0
        var open = -1
        for (i, c) in text.utf16.enumerated() {
            if c == 0x28 {
                if depth == 0 { open = i }
                depth += 1
            } else if c == 0x29 && depth > 0 {
                depth -= 1
                if depth == 0 { found.append(Bracket(start: open, end: i + 1, contentStart: open + 1, contentEnd: i)) }
            }
        }
        let length = text.u16Count
        if depth > 0 { found.append(Bracket(start: open, end: length, contentStart: open + 1, contentEnd: length)) }
        return found
    }

    /// What a bracket after the name holds (#63).
    enum BracketKind {
        /// No amount with a unit: "(packed)", "(Note 2)", "(2-inch pieces)". Left alone.
        case other
        /// A package or per-item size: "1 can (14 oz)", "2 (400 g) tins", "(8 oz each)". Never scaled.
        case package
        /// The line's own amount written another way: "(8 ½ ounces)", "(about 1/4 cup)". Scales with it.
        case total
        /// Anything else holding an amount: scaling beside it could contradict it.
        case unsure
    }

    /// `before` is the side's text before the bracket, `measure` whether the side's amount has a
    /// unit, and `one` whether it is the count 1. Only a measure's bracket can't be a per-item
    /// size: "4 Apfel (ca. 800g)" and "1 patate douce (300-400 g)" may give each one's weight, so
    /// a count's bracket is a package size or unsure, never a total.
    static func kind(_ p: Patterns, before: String, content: String, measure: Bool, one: Bool) -> BracketKind {
        if !p.qtyUnit.containsMatch(in: content) { return .other }
        if (!measure && before.kIsBlank) || p.afterContainer.containsMatch(in: before) ||
            (!measure && one && p.containerFirst.containsMatch(in: before)) ||
            p.perItem.containsMatch(in: content) { return .package }
        if measure && p.total.matchEntire(unwrapped(content)) != nil { return .total }
        return .unsure
    }

    // "((~250g/8oz))": recipetineats.com doubles every bracket.
    private static func unwrapped(_ content: String) -> String {
        var text = content.kTrimmed
        while text.u16Count >= 2 && text.hasPrefix("(") && text.hasSuffix(")") {
            let inner = text.u16Substring(1, text.u16Count - 1)
            if !balanced(inner) { break }
            text = inner.kTrimmed
        }
        return text
    }

    private static func balanced(_ text: String) -> Bool {
        var depth = 0
        for c in text.utf16 {
            if c == 0x28 { depth += 1 } else if c == 0x29 {
                depth -= 1
                if depth < 0 { return false }
            }
        }
        return depth == 0
    }

    /// Scales the totals in brackets after the name, leaving package sizes and other brackets as
    /// written. Nil when a bracket is `.unsure`: the line stays as written.
    private static func scaleBrackets(
        _ p: Patterns, _ tail: String, factor: Double, comma: Bool, measure: Bool, one: Bool
    ) -> String? {
        var out = ""
        var cursor = 0
        for b in brackets(tail) {
            let content = tail.u16Substring(b.contentStart, b.contentEnd)
            switch kind(p, before: tail.u16Substring(0, b.start), content: content, measure: measure, one: one) {
            case .other, .package: continue
            case .unsure: return nil
            case .total:
                guard let scaled = scaleTotal(p, content, factor: factor, comma: comma) else { return nil }
                out += tail.u16Substring(cursor, b.contentStart) + scaled
                cursor = b.contentEnd
            }
        }
        return out + tail.u16Substring(from: cursor)
    }

    private static func scaleTotal(_ p: Patterns, _ content: String, factor: Double, comma: Bool) -> String? {
        var failed = false
        let scaled = p.measure.replace(content) { m in
            let upper = m[3]
            guard let unit = MeasureUnit.fromText(m[5], words: p.words), let low = p.parse(m[1]) else {
                failed = true
                return m.value
            }
            var range = ""
            if !upper.isEmpty {
                guard let high = p.parse(upper) else {
                    failed = true
                    return m.value
                }
                range = m[2] + formatFor(unit, high * factor, comma: comma)
            }
            return formatFor(unit, low * factor, comma: comma) + range + m[4] + m[5]
        }
        return failed ? nil : scaled
    }

    private static func scalePair(_ p: Patterns, _ match: JMatch, factor: Double, comma: Bool) -> String {
        guard let unit = MeasureUnit.fromText(match[3], words: p.words) else { return match.value }
        guard let value = p.parse(match[1]) else { return match.value }
        return formatFor(unit, value * factor, comma: comma) + match[2] + match[3]
    }

    // Metric amounts read better as "240" or "7.5" than as "240" or "7 1/2".
    private static func formatFor(_ unit: MeasureUnit, _ value: Double, comma: Bool) -> String {
        if unit.metric { return withSeparator(formatMetric(value), comma: comma) }
        if comma { return withSeparator(plainDecimal(value, scale: 2), comma: true) }
        return format(value)
    }

    static func formatMetric(_ value: Double) -> String {
        plainDecimal(value, scale: value >= 10 ? 0 : 1)
    }

    // The language's word between a whole number and its fraction, which the quantity pattern
    // has already checked: "2 and 1/2" is "2 1/2" in any language.
    private static let joiner = JRegex(#"\s+\p{L}[\p{L}\s]*?\s+(?=[\d"# + unicodeFractions + #"])"#)

    /// `thousandsDot`: the quantity comes from a language that writes "1.500" for 1500.
    static func parse(_ quantity: String, thousandsDot: Bool = false) -> Double? {
        // `qty` only lets a comma through as a decimal comma, never before three digits.
        // "2 and 1/2" is "2 1/2", and "1⁄2" (U+2044) is "1/2".
        let digits = thousandsDot ? thousandsDotSeparator.replace(quantity.kTrimmed, with: "") : quantity.kTrimmed
        let q = joiner.replace(
            digits.replacingOccurrences(of: ",", with: ".").replacingOccurrences(of: "⁄", with: "/"),
            with: " "
        )
        guard let last = q.last else { return nil }
        if let fraction = unicodeValues[last] {
            let whole = String(q.dropLast()).kTrimmed
            if whole.isEmpty { return fraction }
            guard let w = Double(whole) else { return nil }
            return w + fraction
        }
        var total = 0.0
        for part in whitespace.split(q) {
            if part.contains("/") {
                let pieces = part.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
                guard pieces.count >= 2, let n = Double(pieces[0]), let d = Double(pieces[1]) else { return nil }
                if d == 0.0 { return nil }
                total += n / d
            } else {
                guard let v = Double(part) else { return nil }
                total += v
            }
        }
        return total
    }

    static func format(_ value: Double) -> String {
        guard value.isFinite else { return plainDecimal(value, scale: 2) }
        let whole = kotlinToInt(value.rounded(.down)) // Kotlin: floor(value).toInt()
        let fraction = value - Double(whole)
        // `min(by:)` keeps the first of equal candidates, as Kotlin's `minBy` does.
        let nearest = fractions.min { abs($0.value - fraction) < abs($1.value - fraction) }!
        if abs(nearest.value - fraction) <= tolerance {
            let w = nearest.value == 1.0 ? Int(Int32(whole) &+ 1) : whole // Kotlin Int wraps
            if !nearest.text.isEmpty { return w == 0 ? nearest.text : "\(w) \(nearest.text)" }
            if w > 0 { return "\(w)" }
        }
        return plainDecimal(value, scale: 2)
    }
}
