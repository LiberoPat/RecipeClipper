import Foundation

/// Converts the leading amount of an ingredient line ("2 cups flour", "8 oz butter") into
/// another `UnitSystem`. Pure: string in, string out.
///
/// Rules that keep it honest:
/// - Weight to weight (oz, lb, g, kg) is exact, and needs no lookup.
/// - Volume to weight needs `IngredientDensities`; an ingredient not in the table is left
///   as written rather than guessed.
/// - If the line already carries the target unit in parentheses or after a slash, as in
///   "1 cup (120 g) flour", the site's own figure is used instead of a calculated one. So is a
///   range after a slash ("250 - 300 g / 8 - 10 oz pasta" in ounces is "8 - 10 oz pasta").
/// - Pourable liquids are left alone in ounces unless `includeLiquids` is set.
///   Metric turns them into ml, which is exact and needs no density, so it ignores the flag.
/// - Metric otherwise gives spoons and cups as ml, except that a line carrying the site's own
///   weight ("1 tsp (4 g) salt", "1 cup/4 oz walnuts") shows that weight in g/kg, even for an
///   ingredient missing from the table or listed there as a skip. Known liquids keep ml even
///   then ("1 cup (245 g) milk" is 240 ml), and a range has no single figure, so it keeps ml.
/// - Bare "oz" is a weight, unless the ingredient is a known liquid, where it means fl oz.
/// - A compound amount ("1 cup plus 2 tbsp flour") is converted as a whole or not at all:
///   an alternate measure after the second part is used for the total, otherwise both parts
///   are converted and summed ("minus 2 tbsp" is subtracted, #62), and if either can't be the
///   line is left as written.
/// - A total in brackets after the name ("1 ⅔ cups bread flour (8 ½ ounces)", #63) is the site's
///   figure too, and is dropped once it is the line's amount. A package size never is.
/// - Alternatives ("8 oz butter or 1 cup oil", #61) and later parts ("plus 2 tbsp") each convert,
///   or the line is left as written; an amount with no unit, or already in the system's units,
///   is fine as it is.
/// - "1,5 kg" is 1.5 kg and converts with a comma ("1,13 kg"); "1,500 g" could be 1.5 g or
///   1500 g, so a line holding a comma before three digits is left as written.
enum UnitConverter {

    private static let gramsPerOunce = 28.3495
    private static let mlPerCup = 236.588

    // Kitchen-metric equivalents for volume -> ml (a cup is 240 ml on US nutrition labels).
    private static let kitchenMl: [MeasureUnit: Double] = [
        .tsp: 5.0,
        .tbsp: 15.0,
        .cup: 240.0,
        .cup200: 200.0,
        .riceCup: 180.0,
        .flOz: 30.0,
        .ml: 1.0,
        .l: 1000.0,
        .cl: 10.0,
        .dl: 100.0,
    ]

    private static let parenAtStart = JRegex(#"^\s*\(([^)]*)\)"#)

    /// The patterns that read one language's unit and amount words (IngredientName reads them too).
    final class Patterns {
        let words: LanguageWords
        let scaler: IngredientScaler.Patterns
        let unitAtStart: JRegex
        let slashAtStart: JRegex

        // "250 - 300 g / 8 - 10 oz pasta": a range written a second way.
        // groups: 1 low, 2 range separator, 3 high, 4 space, 5 unit
        let slashRangeAtStart: JRegex

        // "plus 1 Tbsp." or "minus 2 tablespoons" straight after the first unit.
        // groups: 1 a subtraction's word, 2 quantity, 3 space, 4 unit
        let continuationAtStart: JRegex

        init(_ words: LanguageWords) {
            self.words = words
            scaler = IngredientScaler.patterns(words)
            let units = UnitPatterns.of(words)
            unitAtStart = JRegex(#"^\s*"# + units.captured, ignoreCase: true)
            slashAtStart = JRegex(
                #"^\s*/\s*("# + scaler.qty + #")(\s*)"# + units.captured,
                ignoreCase: true
            )
            slashRangeAtStart = JRegex(
                #"^\s*/\s*("# + scaler.qty + #")(\s*[-–—]\s*|\s+"# + words.rangeWords + #"\s+)("# + scaler.qty + #")(\s*)"# +
                    units.captured,
                ignoreCase: true
            )
            continuationAtStart = JRegex(
                #"^\s*(?:"# + scaler.continuation + "|(" + scaler.subtraction + "))(" + scaler.qty + #")(\s*)"# +
                    units.captured,
                ignoreCase: true
            )
        }
    }

    static func patterns(_ words: LanguageWords) -> Patterns { words.compiled(Patterns.self, Patterns.init) }

    /// The second half of a compound amount, "plus 2 tbsp"; negative for "minus 2 tbsp".
    private struct Part {
        let quantity: Double
        let unit: MeasureUnit
    }

    /// A second measure written next to the first: `base` is grams or ml, `text` as written.
    private struct Measure {
        let base: Double
        let unit: MeasureUnit
        let text: String
    }

    /// `length` is how much of the text after the unit the alternate measure occupied.
    private struct Alternate {
        let weight: Measure?
        let volume: Measure?
        let length: Int
    }

    private struct Amount {
        let low: Double
        let high: Double?
        let separator: String
    }

    /// `separatorFrom` is the line whose decimal separator the result follows (default: `line`):
    /// the line as the recipe wrote it, when `line` is that line already scaled ("2,5 lb"
    /// doubled is "5 lb", which no longer shows its comma). `words` nil: a language the app has
    /// no words for, so the line stays as written.
    static func convert(
        _ line: String, system: UnitSystem, includeLiquids: Bool, separatorFrom: String? = nil,
        words: LanguageWords? = .english
    ) -> String {
        guard system != .asWritten, let words else { return line }
        let p = patterns(words)
        if p.scaler.amountAfterName { return convertTrailing(line, system: system, includeLiquids: includeLiquids, words: words) }
        if p.scaler.unreadable(line) { return line }
        let comma = IngredientScaler.decimalComma.containsMatch(in: separatorFrom ?? line)

        // "1 cup butter or 1/2 cup oil" (#61): every amount converts, or the line stays as written.
        // An amount with no unit ("2 vanilla pods") or already in the system's units is fine as it is.
        guard let sides = IngredientScaler.sides(p.scaler, line), sides.count > 1 else {
            if case .converted(let text) = convertSide(p, line, system: system, includeLiquids: includeLiquids, comma: comma) {
                return text
            }
            return line
        }
        let converted = sides.map {
            convertSide(p, line.u16Substring($0.lowerBound, $0.upperBound), system: system,
                        includeLiquids: includeLiquids, comma: comma)
        }
        if converted.contains(where: { if case .failed = $0 { return true } else { return false } }) { return line }
        if !converted.contains(where: { if case .converted = $0 { return true } else { return false } }) { return line }
        var out = ""
        for (i, range) in sides.enumerated() {
            if case .converted(let text) = converted[i] {
                out += text
            } else {
                out += line.u16Substring(range.lowerBound, range.upperBound)
            }
            let next = i + 1 < sides.count ? sides[i + 1].lowerBound : line.u16Count
            out += line.u16Substring(range.upperBound, next)
        }
        return out
    }

    /// One amount of a line, converted.
    private enum Side {
        case converted(String)
        /// Nothing to convert: a count, or already in the system's units.
        case asItIs
        /// An amount this system can't show honestly: the whole line stays as written.
        case failed
    }

    private static func convertSide(
        _ p: Patterns, _ line: String, system: UnitSystem, includeLiquids: Bool, comma: Bool
    ) -> Side {
        let words = p.words
        guard let lead = p.scaler.leading.find(line) else { return .failed }
        let afterQty = line.u16Substring(from: lead.end)
        if p.scaler.notAnAmount.containsMatch(in: afterQty) { return .failed }

        guard let low = p.scaler.parse(lead[2]) else { return .failed }
        let upperText = lead[4]
        var high: Double? = nil
        if !upperText.isEmpty {
            guard let h = p.scaler.parse(upperText) else { return .failed }
            high = h
        }
        let amount = Amount(low: low, high: high, separator: lead[3])

        guard let unitMatch = p.unitAtStart.find(afterQty),
              let unit = MeasureUnit.fromText(unitMatch[1], words: words) else { return .asItIs }
        if ownUnits(system).contains(unit) { return .asItIs }
        // A "tasse" or "Tasse" has no one size: it scales, but never converts.
        if unit == .varies { return .failed }

        var after = afterQty.u16Substring(from: unitMatch.end)

        // "1½ cups plus 1 Tbsp.": converting only the first part would be confidently wrong.
        // "2 cups minus 2 tablespoons" takes the second part away (#62).
        var extra: Part? = nil
        if let c = p.continuationAtStart.find(after) {
            if amount.high != nil { return .failed } // a range plus a part: leave it
            guard let unit2 = MeasureUnit.fromText(c[4], words: words), unit2 != .varies else { return .failed }
            guard let quantity2 = p.scaler.parse(c[2]) else { return .failed }
            extra = Part(quantity: c[1].isEmpty ? quantity2 : -quantity2, unit: unit2)
            after = after.u16Substring(from: c.end)
        }

        // "/ 8 - 10 oz": the site's range in another unit. It is consumed like "/120 g", and
        // shown instead of a calculated range when it is already in the target unit.
        var siteRange: (unit: MeasureUnit, text: String)? = nil
        if extra == nil, let r = p.slashRangeAtStart.find(after) {
            guard let rangeUnit = MeasureUnit.fromText(r[5], words: words) else { return .failed }
            if p.scaler.parse(r[1]) == nil || p.scaler.parse(r[3]) == nil { return .failed }
            let value = r.value
            let slash = value.firstIndex(of: "/")!
            siteRange = (rangeUnit, String(value[value.index(after: slash)...]).kTrimmed)
            after = after.u16Substring(from: r.end)
        }

        var alternate = siteRange == nil ? findAlternate(p, after) : nil
        if let alternate { after = after.u16Substring(from: alternate.length) }
        // "1 ⅔ cups bread flour (8 ½ ounces)": the site's total after the name (#63), used like
        // "(120 g)" after the unit, and dropped when it becomes the line's amount.
        var total: IngredientScaler.Bracket? = nil
        if alternate == nil && siteRange == nil {
            let text = after
            total = IngredientScaler.brackets(text).first { b in
                IngredientScaler.kind(
                    p.scaler, before: text.u16Substring(0, b.start),
                    content: text.u16Substring(b.contentStart, b.contentEnd), measure: true, one: false
                ) == .total
            }
            alternate = total.flatMap { alternateIn(p, text.u16Substring($0.contentStart, $0.contentEnd)) }
        }

        let density = IngredientDensities.find(after, words: words)
        let isLiquid = density?.liquid == true
        let effective: MeasureUnit = (unit == .oz && isLiquid) ? .flOz : unit
        let extraPart = extra.map {
            Part(quantity: $0.quantity, unit: ($0.unit == .oz && isLiquid) ? .flOz : $0.unit)
        }
        if effective == .stick && density?.stickable != true { return .failed }
        if extraPart?.unit == .stick && density?.stickable != true { return .failed }

        let anyVolume = effective.kind == .volume || extraPart?.unit.kind == .volume
        let allVolume = effective.kind == .volume && (extraPart == nil || extraPart?.unit.kind == .volume)
        if system != .metric && anyVolume && isLiquid && !includeLiquids { return .failed }

        // Metric keeps volumes as ml unless the ingredient is a known solid, which is weighed,
        // or the site wrote its own weight beside a non-liquid ("1 tsp (4 g) salt"). A range has
        // no single figure to borrow, the same guard weightAmount applies.
        let siteWeight = alternate?.weight != nil && amount.high == nil
        let asWeight = system != .metric || !allVolume ||
            (!isLiquid && (density?.gramsPerCup != nil || siteWeight))

        var site: String? = nil
        if let siteRange, ownUnits(system).contains(siteRange.unit) {
            let fits = asWeight
                ? siteRange.unit.kind == .weight && !(siteRange.unit == .oz && isLiquid)
                : siteRange.unit.kind == .volume && siteRange.unit.metric
            if fits { site = siteRange.text }
        }

        // A range has no single figure to borrow, and "(8 oz)" beside a liquid means fl oz.
        let used: Measure?
        if asWeight {
            used = alternate?.weight.flatMap { (amount.high == nil && !($0.unit == .oz && isLiquid)) ? $0 : nil }
        } else {
            used = alternate?.volume.flatMap { amount.high == nil ? $0 : nil }
        }
        let calculated: String?
        if asWeight {
            calculated = weightAmount(amount, effective, extraPart, density, used, isLiquid, system)
        } else {
            calculated = volumeAmount(amount, effective, extraPart, used)
        }
        guard let converted = site ?? calculated else { return .failed }
        if let total, used != nil {
            after = trimEnd(after.u16Substring(0, total.start)) + after.u16Substring(from: total.end)
        }
        return .converted(lead[1] + IngredientScaler.withSeparator(converted, comma: comma) + after)
    }

    /// Kotlin's `trimEnd()`.
    private static func trimEnd(_ text: String) -> String {
        var s = Substring(text)
        while let last = s.last, last.isWhitespace { s = s.dropLast() }
        return String(s)
    }

    /// A "name amount" line ("砂糖 大さじ2", "水 2カップ", #16), by the same rules: a counter (個,
    /// 本) has no unit and stays as written, a liquid follows `includeLiquids`, and a measure in
    /// brackets straight after the unit ("1/2カップ（100ml）") is the site's own figure.
    private static func convertTrailing(
        _ line: String, system: UnitSystem, includeLiquids: Bool, words: LanguageWords
    ) -> String {
        guard let found = TrailingAmount.find(line, words: words), let unit = found.unit else { return line }
        if unit == .varies || ownUnits(system).contains(unit) { return line }

        let density = TrailingAmount.nameOf(found.name, words: words).flatMap { IngredientDensities.find($0, words: words) }
        let isLiquid = density?.liquid == true
        let isVolume = unit.kind == .volume
        if system != .metric && isVolume && isLiquid && !includeLiquids { return line }

        let amount = Amount(low: found.low.value, high: found.high?.value, separator: found.separator)
        let alternate = found.measure.flatMap { $0.atStart ? $0 : nil }
        func measure(_ kind: MeasureKind) -> Measure? {
            guard let a = alternate, a.unit.kind == kind, kind == .weight || a.unit.metric else { return nil }
            return Measure(base: a.number.value * a.unit.base, unit: a.unit, text: a.text)
        }
        let weight = measure(.weight)
        let siteWeight = weight != nil && amount.high == nil
        let asWeight = system != .metric || !isVolume || (!isLiquid && (density?.gramsPerCup != nil || siteWeight))
        let converted = asWeight
            ? weightAmount(amount, unit, nil, density, weight, isLiquid, system)
            : volumeAmount(amount, unit, nil, measure(.volume))
        guard let converted else { return line }
        return line.u16Substring(0, found.start) + found.beforeWord + converted +
            line.u16Substring(from: alternate?.end ?? found.restStart)
    }

    private static func ownUnits(_ system: UnitSystem) -> Set<MeasureUnit> {
        switch system {
        case .ounces: return [.oz, .lb]
        case .metric: return [.g, .kg, .ml, .l, .cl, .dl]
        case .asWritten: return []
        }
    }

    private static func weightAmount(
        _ amount: Amount,
        _ unit: MeasureUnit,
        _ extra: Part?,
        _ density: Density?,
        _ altWeight: Measure?,
        _ isLiquid: Bool,
        _ system: UnitSystem
    ) -> String? {
        // A range has no single figure to borrow, and "(8 oz)" beside a liquid means fl oz.
        let alt = altWeight.flatMap { a in
            (amount.high == nil && !(a.unit == .oz && isLiquid)) ? a : nil
        }
        if let alt, ownUnits(system).contains(alt.unit) { return alt.text.kTrimmed }

        let gramsLow: Double
        let gramsHigh: Double?
        if let alt {
            gramsLow = alt.base
            gramsHigh = nil
        } else {
            func grams(_ quantity: Double, _ u: MeasureUnit) -> Double? {
                if u.kind == .weight { return quantity * u.base }
                return density?.gramsPerCup.map { quantity * u.base * ($0 / mlPerCup) }
            }
            var extraGrams = 0.0
            if let extra {
                guard let g = grams(extra.quantity, extra.unit) else { return nil }
                extraGrams = g
            }
            guard let low = grams(amount.low, unit) else { return nil }
            gramsLow = low + extraGrams
            if let h = amount.high {
                guard let g = grams(h, unit) else { return nil }
                gramsHigh = g
            } else {
                gramsHigh = nil
            }
        }
        if system == .ounces {
            return ounceText(gramsLow, gramsHigh, amount.separator)
        }
        return metricText(gramsLow, gramsHigh, amount.separator, small: "g", large: "kg")
    }

    private static func volumeAmount(_ amount: Amount, _ unit: MeasureUnit, _ extra: Part?, _ altVolume: Measure?) -> String? {
        if let alt = altVolume, amount.high == nil { return alt.text.kTrimmed }
        guard let ml = kitchenMl[unit] else { return nil }
        var extraMl = 0.0
        if let extra {
            guard let perUnit = kitchenMl[extra.unit] else { return nil }
            extraMl = extra.quantity * perUnit
        }
        return metricText(
            amount.low * ml + extraMl, amount.high.map { $0 * ml }, amount.separator, small: "ml", large: "L"
        )
    }

    // MARK: - Alternate measures: "1 cup (120 g) flour", "1 cup/120 grams flour"

    private static func findAlternate(_ p: Patterns, _ after: String) -> Alternate? {
        if let m = parenAtStart.find(after) {
            // e.g. "(packed)": not a measure, leave it
            return alternateIn(p, m[1], length: m.value.u16Count)
        }
        if let m = p.slashAtStart.find(after) {
            let value = m.value
            let text: String
            if let slash = value.firstIndex(of: "/") {
                text = String(value[value.index(after: slash)...]).kTrimmed
            } else {
                text = value.kTrimmed
            }
            guard let pair = p.scaler.qtyUnit.matchEntire(text) else { return nil }
            return Alternate(
                weight: measureOf(p, pair, .weight),
                volume: measureOf(p, pair, .volume),
                length: value.u16Count
            )
        }
        return nil
    }

    /// The measures in a bracket's `content`; nil when it holds none.
    private static func alternateIn(_ p: Patterns, _ content: String, length: Int = 0) -> Alternate? {
        let pairs = p.scaler.qtyUnit.findAll(content)
        if pairs.isEmpty { return nil }
        return Alternate(
            weight: pairs.lazy.compactMap { measureOf(p, $0, .weight) }.first,
            volume: pairs.lazy.compactMap { measureOf(p, $0, .volume) }.first,
            length: length
        )
    }

    /// A weight (g, kg, oz, lb) or a metric volume (ml, cl, dl, l) from a "quantity unit" match.
    private static func measureOf(_ p: Patterns, _ match: JMatch, _ kind: MeasureKind) -> Measure? {
        guard let unit = MeasureUnit.fromText(match[3], words: p.words) else { return nil }
        let wanted: Bool
        switch kind {
        case .weight: wanted = unit.kind == .weight
        case .volume: wanted = unit.kind == .volume && unit.metric
        case .none: wanted = false
        }
        if !wanted { return nil }
        guard let quantity = p.scaler.parse(match[1]) else { return nil }
        return Measure(base: quantity * unit.base, unit: unit, text: match.value)
    }

    // MARK: - Formatting

    /// Grams as g/kg, or millilitres as ml/L: one decimal under 10, whole numbers up to 100, then 5s.
    private static func metricText(_ low: Double, _ high: Double?, _ separator: String, small: String, large: String) -> String? {
        let top = Swift.max(low, high ?? low)
        if top < 0.5 { return nil }
        let useLarge = top >= 1000
        func part(_ v: Double) -> String { useLarge ? formatThousands(v) : formatSmall(v) }
        let body = high.map { part(low) + separator + part($0) } ?? part(low)
        return "\(body) \(useLarge ? large : small)"
    }

    // Kotlin's `kotlin.math.round` is half-to-even, hence `.toNearestOrEven` throughout.
    private static func formatSmall(_ v: Double) -> String {
        let rounded: Double
        if v < 10 {
            rounded = (v * 2).rounded(.toNearestOrEven) / 2
        } else if v < 100 {
            rounded = v.rounded(.toNearestOrEven)
        } else {
            rounded = (v / 5).rounded(.toNearestOrEven) * 5
        }
        return plainDecimal(rounded, scale: 1)
    }

    private static func formatThousands(_ v: Double) -> String {
        plainDecimal(v / 1000, scale: 2)
    }

    private static func ounceText(_ gramsLow: Double, _ gramsHigh: Double?, _ separator: String) -> String? {
        let low = gramsLow / gramsPerOunce
        let high = gramsHigh.map { $0 / gramsPerOunce }
        if Swift.max(low, high ?? low) < 0.125 { return nil } // a pinch in ounces means nothing
        if let high {
            return IngredientScaler.format(roundOunces(low)) + separator +
                IngredientScaler.format(roundOunces(high)) + " oz"
        }
        let rounded = roundOunces(low)
        if rounded >= 16 {
            let pounds = kotlinToInt((rounded / 16).rounded(.down))
            let remainder = rounded - Double(Int32(pounds) &* 16) // Kotlin Int arithmetic wraps
            return remainder == 0.0
                ? "\(pounds) lb"
                : "\(pounds) lb \(IngredientScaler.format(remainder)) oz"
        }
        return "\(IngredientScaler.format(rounded)) oz"
    }

    // Eighths under 4 oz, quarters above: every result is a fraction IngredientScaler.format prints.
    private static func roundOunces(_ oz: Double) -> Double {
        oz < 4 ? (oz * 8).rounded(.toNearestOrEven) / 8 : (oz * 4).rounded(.toNearestOrEven) / 4
    }
}
