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

    // "1 1/2", "1½", "1/2", "1.5", "½", "2" — tried in that order.
    static let qty =
        #"(?:\d+\s+\d+/\d+|\d+\s*["# + unicodeFractions + #"]|\d+/\d+|\d+(?:\.\d+)?|["# + unicodeFractions + #"])"#

    // groups: 1 leading space, 2 quantity, 3 range separator, 4 range upper bound
    static let leading = JRegex(#"^(\s*)("# + qty + #")(?:(\s*[-–—]\s*|\s+to\s+)("# + qty + #"))?"#)

    // What follows the number when it is a size or a percentage, not an amount.
    static let notAnAmount = JRegex(#"^\s*-?\s*(?:%|inch(?:es)?\b|cm\b|mm\b)"#, ignoreCase: true)

    // A quantity followed by a unit. groups: 1 quantity, 2 space, 3 unit
    static let qtyUnit = JRegex("(" + qty + #")(\s*)"# + UnitPatterns.captured, ignoreCase: true)

    // "1 cup (120 g) flour": a second measure of the same amount, right after a unit word.
    // A parenthesis straight after the number ("1 (14 oz) can") or after a container word
    // ("1 can (14 oz)") is a package size, not an alternate measure, and is never scaled.
    private static let altParen =
        JRegex(#"^(\s*"# + UnitPatterns.plain + #"\s*\()([^)]*)(\))"#, ignoreCase: true)

    // "1 cup/120 grams flour". groups: 1 prefix, 2 quantity, 3 space, 4 unit
    private static let altSlash = JRegex(
        #"^(\s*"# + UnitPatterns.plain + #"\s*/\s*)("# + qty + #")(\s*)"# + UnitPatterns.captured,
        ignoreCase: true
    )

    // The word joining the two parts of a compound amount: "1 cup plus 2 tbsp", "1 cup + 2 tbsp".
    static let continuation = #"(?:plus\s+|and\s+|\+\s*)"#

    // "1 cup plus 2 tbsp (140 g) flour": the second part of a compound amount, which scales
    // with the first. groups: 1 prefix, 2 quantity, 3 space, 4 unit
    private static let continued = JRegex(
        #"^(\s*"# + UnitPatterns.plain + #"\s*"# + continuation + #")("# + qty + #")(\s*)"# + UnitPatterns.captured,
        ignoreCase: true
    )

    // Nearest-fraction table used when formatting; anything further than `tolerance`
    // from all of these falls back to a plain decimal.
    private static let fractions: [(value: Double, text: String)] = [
        (0.0, ""), (1 / 8.0, "1/8"), (1 / 4.0, "1/4"), (1 / 3.0, "1/3"), (3 / 8.0, "3/8"),
        (1 / 2.0, "1/2"), (5 / 8.0, "5/8"), (2 / 3.0, "2/3"), (3 / 4.0, "3/4"),
        (7 / 8.0, "7/8"), (1.0, ""),
    ]
    private static let tolerance = 0.02

    private static let whitespace = JRegex(#"\s+"#)

    static func scale(_ line: String, factor: Double) -> String {
        if factor == 1.0 { return line }
        guard let match = leading.find(line) else { return line }
        let rest = line.u16Substring(from: match.end)
        if notAnAmount.containsMatch(in: rest) { return line }

        guard let low = parse(match[2]) else { return line }
        let upperRaw = match[4]
        let scaled: String
        if upperRaw.isEmpty {
            scaled = format(low * factor)
        } else {
            guard let high = parse(upperRaw) else { return line }
            scaled = format(low * factor) + match[3] + format(high * factor)
        }
        return match[1] + scaled + scaleContinuation(rest, factor: factor)
    }

    /// Scales "plus 2 tbsp" and whatever alternate measure follows it, else just the alternate.
    private static func scaleContinuation(_ rest: String, factor: Double) -> String {
        guard let m = continued.find(rest),
              let unit = MeasureUnit.fromText(m[4]),
              let value = parse(m[2]) else { return scaleAlternateMeasure(rest, factor: factor) }
        let tail = m[3] + m[4] + rest.u16Substring(from: m.end)
        return m[1] + formatFor(unit, value * factor) + scaleAlternateMeasure(tail, factor: factor)
    }

    /// Keeps "(120 g)" or "/120 grams" in step with the leading amount that was just scaled.
    private static func scaleAlternateMeasure(_ rest: String, factor: Double) -> String {
        if let m = altParen.find(rest) {
            let inner = qtyUnit.replace(m[2]) { scalePair($0, factor: factor) }
            return m[1] + inner + m[3] + rest.u16Substring(from: m.end)
        }
        if let m = altSlash.find(rest),
           let unit = MeasureUnit.fromText(m[4]),
           let value = parse(m[2]) {
            return m[1] + formatFor(unit, value * factor) + m[3] + m[4] + rest.u16Substring(from: m.end)
        }
        return rest
    }

    private static func scalePair(_ match: JMatch, factor: Double) -> String {
        guard let unit = MeasureUnit.fromText(match[3]) else { return match.value }
        guard let value = parse(match[1]) else { return match.value }
        return formatFor(unit, value * factor) + match[2] + match[3]
    }

    // Metric amounts read better as "240" or "7.5" than as "240" or "7 1/2".
    private static func formatFor(_ unit: MeasureUnit, _ value: Double) -> String {
        unit.metric ? formatMetric(value) : format(value)
    }

    static func formatMetric(_ value: Double) -> String {
        plainDecimal(value, scale: value >= 10 ? 0 : 1)
    }

    static func parse(_ quantity: String) -> Double? {
        let q = quantity.kTrimmed
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
