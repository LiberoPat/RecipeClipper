import Foundation

/// Scales the leading quantity of an ingredient line ("1 1/2 cups flour",
/// "½ tsp salt", "1-2 tbsp oil"). Pure: string in, string out.
///
/// Only the leading quantity is touched. A line that doesn't start with a number
/// ("salt to taste"), or whose number is a measurement rather than an amount
/// ("1-inch piece ginger", "2% milk"), is returned unchanged. Wrong scaling is worse
/// than no scaling, so anything ambiguous is left alone.
enum IngredientScaler {

    private static let unicodeFractions = "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"

    private static let unicodeValues: [Character: Double] = [
        "¼": 1 / 4.0, "½": 1 / 2.0, "¾": 3 / 4.0, "⅐": 1 / 7.0, "⅑": 1 / 9.0,
        "⅒": 1 / 10.0, "⅓": 1 / 3.0, "⅔": 2 / 3.0, "⅕": 1 / 5.0, "⅖": 2 / 5.0,
        "⅗": 3 / 5.0, "⅘": 4 / 5.0, "⅙": 1 / 6.0, "⅚": 5 / 6.0, "⅛": 1 / 8.0,
        "⅜": 3 / 8.0, "⅝": 5 / 8.0, "⅞": 7 / 8.0,
    ]

    // "1 1/2", "1½", "1/2", "1.5", "1,5", "½", "2" — tried in that order. A comma followed by
    // one or two digits is a decimal comma; one followed by three ("1,500") may be a thousands
    // separator, so it is never read as part of a quantity, and `ambiguousComma` leaves the line.
    static let qty =
        #"(?:\d+\s+\d+/\d+|\d+\s*["# + unicodeFractions + #"]|\d+/\d+|\d+(?:\.\d+|,\d{1,2}(?!\d))?|["# +
        unicodeFractions + #"])"#

    /// "1,5": the line writes decimals with a comma, so its output does too.
    static let decimalComma = JRegex(#"\d,\d{1,2}(?!\d)"#)

    /// "1,500" is 1.5 or 1500 depending on who wrote it: a line holding one is left as written.
    static let ambiguousComma = JRegex(#"\d,\d{3}"#)

    private static let decimalPoint = JRegex(#"(?<=\d)\.(?=\d)"#)

    /// "2.25" as "2,25" when `comma`, for a line that writes its decimals that way.
    static func withSeparator(_ text: String, comma: Bool) -> String {
        comma ? decimalPoint.replace(text, with: ",") : text
    }

    /// The patterns that read one language's words (shared/tables/<language>/amounts.json).
    final class Patterns {
        let words: LanguageWords

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

        // "1 cup/120 grams flour". groups: 1 prefix, 2 quantity, 3 space, 4 unit
        let altSlash: JRegex

        // The word joining the two parts of a compound amount: "1 cup plus 2 tbsp", "1 cup + 2 tbsp".
        let continuation: String

        // "1 cup plus 2 tbsp (140 g) flour": the second part of a compound amount, which scales
        // with the first. groups: 1 prefix, 2 quantity, 3 space, 4 unit
        let continued: JRegex

        init(_ words: LanguageWords) {
            self.words = words
            let units = UnitPatterns.of(words)
            leading = JRegex(#"^(\s*)("# + IngredientScaler.qty + #")(?:(\s*[-–—]\s*|\s+"# + words.rangeWords + #"\s+)("# + IngredientScaler.qty + #"))?"#)
            notAnAmount = JRegex(
                #"^\s*-?\s*(?:"# + (["%"] + words.strings("amounts", "sizes") + [#"cm\b"#, #"mm\b"#]).joined(separator: "|") + ")",
                ignoreCase: true
            )
            qtyUnit = JRegex("(" + IngredientScaler.qty + #")(\s*)"# + units.captured, ignoreCase: true)
            altParen = JRegex(#"^(\s*"# + units.plain + #"\s*\()([^)]*)(\))"#, ignoreCase: true)
            altSlash = JRegex(
                #"^(\s*"# + units.plain + #"\s*/\s*)("# + IngredientScaler.qty + #")(\s*)"# + units.captured,
                ignoreCase: true
            )
            continuation = "(?:" + (words.strings("amounts", "continuation") + [#"\+\s*"#]).joined(separator: "|") + ")"
            continued = JRegex(
                #"^(\s*"# + units.plain + #"\s*"# + continuation + #")("# + IngredientScaler.qty + #")(\s*)"# + units.captured,
                ignoreCase: true
            )
        }
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
        if ambiguousComma.containsMatch(in: line) { return line }
        let p = patterns(words)
        guard let match = p.leading.find(line) else { return line }
        let rest = line.u16Substring(from: match.end)
        if p.notAnAmount.containsMatch(in: rest) { return line }

        let comma = decimalComma.containsMatch(in: line)
        guard let low = parse(match[2]) else { return line }
        let upperRaw = match[4]
        let scaled: String
        if upperRaw.isEmpty {
            scaled = formatLeading(low * factor, comma: comma)
        } else {
            guard let high = parse(upperRaw) else { return line }
            scaled = formatLeading(low * factor, comma: comma) + match[3] + formatLeading(high * factor, comma: comma)
        }
        return match[1] + scaled + scaleContinuation(p, rest, factor: factor, comma: comma)
    }

    // A line that writes "1,5" reads decimals, not fractions: "1,5 kg" x 1.5 is "2,25 kg".
    private static func formatLeading(_ value: Double, comma: Bool) -> String {
        comma ? withSeparator(plainDecimal(value, scale: 2), comma: true) : format(value)
    }

    /// Scales "plus 2 tbsp" and whatever alternate measure follows it, else just the alternate.
    private static func scaleContinuation(_ p: Patterns, _ rest: String, factor: Double, comma: Bool) -> String {
        guard let m = p.continued.find(rest),
              let unit = MeasureUnit.fromText(m[4], words: p.words),
              let value = parse(m[2]) else { return scaleAlternateMeasure(p, rest, factor: factor, comma: comma) }
        let tail = m[3] + m[4] + rest.u16Substring(from: m.end)
        return m[1] + formatFor(unit, value * factor, comma: comma) +
            scaleAlternateMeasure(p, tail, factor: factor, comma: comma)
    }

    /// Keeps "(120 g)" or "/120 grams" in step with the leading amount that was just scaled.
    private static func scaleAlternateMeasure(_ p: Patterns, _ rest: String, factor: Double, comma: Bool) -> String {
        if let m = p.altParen.find(rest) {
            let inner = p.qtyUnit.replace(m[2]) { scalePair(p, $0, factor: factor, comma: comma) }
            return m[1] + inner + m[3] + rest.u16Substring(from: m.end)
        }
        if let m = p.altSlash.find(rest),
           let unit = MeasureUnit.fromText(m[4], words: p.words),
           let value = parse(m[2]) {
            return m[1] + formatFor(unit, value * factor, comma: comma) + m[3] + m[4] + rest.u16Substring(from: m.end)
        }
        return rest
    }

    private static func scalePair(_ p: Patterns, _ match: JMatch, factor: Double, comma: Bool) -> String {
        guard let unit = MeasureUnit.fromText(match[3], words: p.words) else { return match.value }
        guard let value = parse(match[1]) else { return match.value }
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

    static func parse(_ quantity: String) -> Double? {
        // `qty` only lets a comma through as a decimal comma, never before three digits.
        let q = quantity.kTrimmed.replacingOccurrences(of: ",", with: ".")
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
