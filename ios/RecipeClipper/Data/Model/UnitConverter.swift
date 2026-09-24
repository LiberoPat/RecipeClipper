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
///   are converted and summed, and if either can't be the line is left as written.
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
        .flOz: 30.0,
        .ml: 1.0,
        .l: 1000.0,
    ]

    private static let unitAtStart = JRegex(#"^\s*"# + UnitPatterns.captured, ignoreCase: true)
    private static let parenAtStart = JRegex(#"^\s*\(([^)]*)\)"#)
    private static let slashAtStart = JRegex(
        #"^\s*/\s*("# + IngredientScaler.qty + #")(\s*)"# + UnitPatterns.captured,
        ignoreCase: true
    )

    // "250 - 300 g / 8 - 10 oz pasta": a range written a second way.
    // groups: 1 low, 2 range separator, 3 high, 4 space, 5 unit
    private static let slashRangeAtStart = JRegex(
        #"^\s*/\s*("# + IngredientScaler.qty + #")(\s*[-–—]\s*|\s+to\s+)("# + IngredientScaler.qty + #")(\s*)"# +
            UnitPatterns.captured,
        ignoreCase: true
    )

    // "plus 1 Tbsp." straight after the first unit. groups: 1 quantity, 2 space, 3 unit
    private static let continuationAtStart = JRegex(
        #"^\s*"# + IngredientScaler.continuation + "(" + IngredientScaler.qty + #")(\s*)"# + UnitPatterns.captured,
        ignoreCase: true
    )

    /// The second half of a compound amount, "plus 2 tbsp".
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
    /// doubled is "5 lb", which no longer shows its comma).
    static func convert(
        _ line: String, system: UnitSystem, includeLiquids: Bool, separatorFrom: String? = nil
    ) -> String {
        if system == .asWritten { return line }
        if IngredientScaler.ambiguousComma.containsMatch(in: line) { return line }

        guard let lead = IngredientScaler.leading.find(line) else { return line }
        let afterQty = line.u16Substring(from: lead.end)
        if IngredientScaler.notAnAmount.containsMatch(in: afterQty) { return line }

        guard let low = IngredientScaler.parse(lead[2]) else { return line }
        let upperText = lead[4]
        var high: Double? = nil
        if !upperText.isEmpty {
            guard let h = IngredientScaler.parse(upperText) else { return line }
            high = h
        }
        let amount = Amount(low: low, high: high, separator: lead[3])

        guard let unitMatch = unitAtStart.find(afterQty) else { return line }
        guard let unit = MeasureUnit.fromText(unitMatch[1]) else { return line }
        if ownUnits(system).contains(unit) { return line }

        var after = afterQty.u16Substring(from: unitMatch.end)

        // "1½ cups plus 1 Tbsp.": converting only the first part would be confidently wrong.
        var extra: Part? = nil
        if let c = continuationAtStart.find(after) {
            if amount.high != nil { return line } // a range plus a part: leave it
            guard let unit2 = MeasureUnit.fromText(c[3]) else { return line }
            guard let quantity2 = IngredientScaler.parse(c[1]) else { return line }
            extra = Part(quantity: quantity2, unit: unit2)
            after = after.u16Substring(from: c.end)
        }

        // "/ 8 - 10 oz": the site's range in another unit. It is consumed like "/120 g", and
        // shown instead of a calculated range when it is already in the target unit.
        var siteRange: (unit: MeasureUnit, text: String)? = nil
        if extra == nil, let r = slashRangeAtStart.find(after) {
            guard let rangeUnit = MeasureUnit.fromText(r[5]) else { return line }
            if IngredientScaler.parse(r[1]) == nil || IngredientScaler.parse(r[3]) == nil { return line }
            let value = r.value
            let slash = value.firstIndex(of: "/")!
            siteRange = (rangeUnit, String(value[value.index(after: slash)...]).kTrimmed)
            after = after.u16Substring(from: r.end)
        }

        let alternate = siteRange == nil ? findAlternate(after) : nil
        if let alternate { after = after.u16Substring(from: alternate.length) }

        let density = IngredientDensities.find(after)
        let isLiquid = density?.liquid == true
        let effective: MeasureUnit = (unit == .oz && isLiquid) ? .flOz : unit
        let extraPart = extra.map {
            Part(quantity: $0.quantity, unit: ($0.unit == .oz && isLiquid) ? .flOz : $0.unit)
        }
        if effective == .stick && density?.stickable != true { return line }
        if extraPart?.unit == .stick && density?.stickable != true { return line }

        let anyVolume = effective.kind == .volume || extraPart?.unit.kind == .volume
        let allVolume = effective.kind == .volume && (extraPart == nil || extraPart?.unit.kind == .volume)
        if system != .metric && anyVolume && isLiquid && !includeLiquids { return line }

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
                : siteRange.unit == .ml || siteRange.unit == .l
            if fits { site = siteRange.text }
        }

        let calculated: String?
        if asWeight {
            calculated = weightAmount(amount, effective, extraPart, density, alternate?.weight, isLiquid, system)
        } else {
            calculated = volumeAmount(amount, effective, extraPart, alternate?.volume)
        }
        guard let converted = site ?? calculated else { return line }

        let comma = IngredientScaler.decimalComma.containsMatch(in: separatorFrom ?? line)
        return lead[1] + IngredientScaler.withSeparator(converted, comma: comma) + after
    }

    private static func ownUnits(_ system: UnitSystem) -> Set<MeasureUnit> {
        switch system {
        case .ounces: return [.oz, .lb]
        case .metric: return [.g, .kg, .ml, .l]
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

    private static func findAlternate(_ after: String) -> Alternate? {
        if let m = parenAtStart.find(after) {
            let pairs = IngredientScaler.qtyUnit.findAll(m[1])
            if pairs.isEmpty { return nil } // e.g. "(packed)": not a measure, leave it
            return Alternate(
                weight: pairs.lazy.compactMap { measureOf($0, .weight) }.first,
                volume: pairs.lazy.compactMap { measureOf($0, .volume) }.first,
                length: m.value.u16Count
            )
        }
        if let m = slashAtStart.find(after) {
            let value = m.value
            let text: String
            if let slash = value.firstIndex(of: "/") {
                text = String(value[value.index(after: slash)...]).kTrimmed
            } else {
                text = value.kTrimmed
            }
            guard let pair = IngredientScaler.qtyUnit.matchEntire(text) else { return nil }
            return Alternate(
                weight: measureOf(pair, .weight),
                volume: measureOf(pair, .volume),
                length: value.u16Count
            )
        }
        return nil
    }

    /// A weight (g, kg, oz, lb) or a metric volume (ml, l) from a "quantity unit" match.
    private static func measureOf(_ match: JMatch, _ kind: MeasureKind) -> Measure? {
        guard let unit = MeasureUnit.fromText(match[3]) else { return nil }
        let wanted: Bool
        switch kind {
        case .weight: wanted = unit.kind == .weight
        case .volume: wanted = unit == .ml || unit == .l
        }
        if !wanted { return nil }
        guard let quantity = IngredientScaler.parse(match[1]) else { return nil }
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
