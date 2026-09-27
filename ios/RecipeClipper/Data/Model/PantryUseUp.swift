import Foundation

/// What cooking a recipe did to one pantry item (#147; Android's `UseUpRow`), as the
/// end-of-cooking sheet offers it: `item`, the ticked `lines` that used it (as the recipe showed
/// them: scaled and converted), and the `change` worked out from them.
struct UseUpRow: Equatable {
    let item: PantryItem
    let lines: [String]
    let change: UseUpChange
}

enum UseUpChange: Equatable {
    /// The quantity worked out: `before` as stored, less the lines, is `after`, written the same
    /// way ("2 lb" is "1 lb"). `after` nil: used up, so the item goes out of stock and onto the
    /// grocery list.
    case subtract(before: String, after: String?)

    /// How much was used can't be worked out: the cook picks keep, low or out.
    case ask
}

/// Using up the pantry when a recipe is cooked (#147; Android's `PantryUseUp`, the rules there).
/// Pure: the ticked lines and the pantry in, the changes out; nothing is written here, and
/// nothing is guessed.
enum PantryUseUp {

    static func rows(
        _ lines: [String], language: String?, pantry: [PantryItem], decisions: Decisions = .none
    ) -> [UseUpRow] {
        guard let words = LanguageWords.forTag(language) else { return [] }
        var order: [Int64] = []
        var used: [Int64: [String]] = [:]
        var items: [Int64: PantryItem] = [:]
        for line in lines {
            guard GrocerySources.buyable(line), let name = IngredientName.of(line, words: words),
                  let item = PantryMatch.find(name, language: words.language, pantry: pantry, decisions: decisions),
                  !item.alwaysHave, item.inStock else { continue }
            if used[item.id] == nil { order.append(item.id) }
            items[item.id] = item
            used[item.id, default: []].append(line.kTrimmed)
        }
        return order.map { id in
            let item = items[id]!
            return UseUpRow(item: item, lines: used[id]!, change: change(item, used[id]!, words))
        }
    }

    /// What `lines` do to `item`'s quantity, both read with `words`.
    static func change(_ item: PantryItem, _ lines: [String], _ words: LanguageWords) -> UseUpChange {
        guard let quantity = item.quantity?.kTrimmed, !quantity.isEmpty,
              let stock = read(quantity, words, pantryName: item.name) else { return .ask }
        var uses: [Amount] = []
        for line in lines {
            guard let use = read(line, words, pantryName: nil) else { return .ask }
            uses.append(use)
        }
        return subtract(quantity, stock, uses) ?? .ask
    }

    /// One exact amount: `value` in `unit` (nil: a count), `unitText` as written, the number and
    /// unit at `start` until `end` of the text (UTF-16), `density` the ingredient's, and `site` a
    /// second measure of the same amount the line wrote ("(120 g)"): its unit and quantity.
    private struct Amount {
        let value: Double
        let unit: MeasureUnit?
        let unitText: String
        let start: Int
        let end: Int
        let comma: Bool
        let density: Density?
        var site: (unit: MeasureUnit, quantity: Double)? = nil
    }

    /// The one exact amount `text` holds, or nil. `pantryName` set: `text` is that pantry item's
    /// quantity ("2 lb", "6", "6 eggs"), where a bare number counts the item; else a recipe line.
    private static func read(_ text: String, _ words: LanguageWords, pantryName: String?) -> Amount? {
        let p = IngredientScaler.patterns(words)
        if p.amountAfterName || p.unreadable(text) { return nil }
        // "1 cup butter or 1/2 cup oil", "2 eggs plus 3 yolks": more than one amount.
        guard IngredientScaler.sides(p, text)?.count == 1 else { return nil }
        let c = UnitConverter.patterns(words)
        guard let lead = p.leading.find(text) else { return nil }
        if !lead[4].isEmpty { return nil } // a range is no one figure
        let afterNumber = lead.end
        let rest = text.u16Substring(from: afterNumber)
        if p.notAnAmount.containsMatch(in: rest) || p.temperature(lead, rest) { return nil }
        guard let value = p.parse(lead[2]), value > 0 else { return nil }
        let start = lead.ranges[2].location
        let comma = IngredientScaler.decimalComma.containsMatch(in: text)

        guard let unitMatch = c.unitAtStart.find(rest) else {
            guard GroceryCombiner.notesOnly(rest, c), countsItself(text, rest, words, pantryName: pantryName) else { return nil }
            return Amount(value: value, unit: nil, unitText: "", start: start, end: afterNumber, comma: comma, density: nil)
        }
        guard var unit = MeasureUnit.fromText(unitMatch[1], words: words), unit != .varies else { return nil }
        let end = afterNumber + unitMatch.end
        var after = text.u16Substring(from: end)
        // "1 cup plus 2 tbsp" is two amounts.
        if c.continuationAtStart.containsMatch(in: after) { return nil }
        var site: (unit: MeasureUnit, quantity: Double)?
        if pantryName == nil, let (measure, length) = secondMeasure(after, p, c) {
            site = measure
            after = after.u16Substring(from: length)
        }
        if !GroceryCombiner.notesOnly(after, c) { return nil }
        let density = IngredientDensities.find(pantryName ?? after, words: words)
        // A bare "oz" of a known liquid is fl oz, as the converter reads it.
        if unit == .oz && density?.liquid == true { unit = .flOz }
        if unit == .stick && density?.stickable != true { return nil }
        return Amount(
            value: value, unit: unit, unitText: unitMatch[1].kTrimmed, start: start, end: end, comma: comma,
            density: density, site: site
        )
    }

    /// "1 cup (120 g) flour", "1 cup/120 g flour": the same amount measured again, straight after
    /// the unit. Its unit and quantity (nil for a measure with no size, "(1 tasse)"), and how much
    /// of `after` it took (UTF-16); nil when there is none.
    private static func secondMeasure(
        _ after: String, _ p: IngredientScaler.Patterns, _ c: UnitConverter.Patterns
    ) -> ((unit: MeasureUnit, quantity: Double)?, Int)? {
        let units = Array(after.utf16)
        if let open = units.firstIndex(where: { !($0 == 32 || (9...13).contains($0)) }), units[open] == 40 /* ( */ {
            guard let close = units[open...].firstIndex(of: 41 /* ) */) else { return nil }
            guard let match = p.qtyUnit.matchEntire(after.u16Substring(open + 1, close).kTrimmed) else { return nil }
            return (measure(match[1], match[3], p), close + 1)
        }
        guard let slash = c.slashAtStart.find(after) else { return nil }
        return (measure(slash[1], slash[3], p), slash.end)
    }

    private static func measure(_ quantity: String, _ unitText: String, _ p: IngredientScaler.Patterns) -> (unit: MeasureUnit, quantity: Double)? {
        guard let unit = MeasureUnit.fromText(unitText, words: p.words), unit != .varies,
              let value = p.parse(quantity) else { return nil }
        return (unit, value)
    }

    private final class CountSizes {
        let sizes: Set<String>
        init(_ words: LanguageWords) { sizes = Set(words.strings("names", "countSizes")) }
    }

    private static let whitespace = JRegex(#"\s+"#)

    /// True when a count counts the ingredient itself: nothing but sizes between the number and
    /// the name ("2 large eggs"), never a part or a package ("2 cloves garlic", "1 can
    /// tomatoes"). A pantry quantity may be a bare number, which counts its item.
    private static func countsItself(_ text: String, _ rest: String, _ words: LanguageWords, pantryName: String?) -> Bool {
        if pantryName != nil && rest.kIsBlank { return true }
        guard let name = IngredientName.of(text, words: words) else { return false }
        if let pantryName, !IngredientName.matches(name, pantryName, words: words) { return false }
        let restWords = whitespace.split(rest.kTrimmed).map { word in
            String(word.reversed().drop { ",.;:".contains($0) }.reversed()).lowercased()
        }
        let nameWords = name.components(separatedBy: " ")
        guard restWords.count >= nameWords.count,
              let at = (0...(restWords.count - nameWords.count))
                .first(where: { Array(restWords[$0..<($0 + nameWords.count)]) == nameWords }) else { return false }
        let sizes = words.compiled(CountSizes.self, CountSizes.init).sizes
        return restWords.prefix(at).allSatisfy { sizes.contains($0) }
    }

    private static let epsilon = 1e-9

    private static func usedUp(_ left: Double, _ total: Double) -> Bool { left <= epsilon * Swift.max(1.0, total) }

    /// `stock` less `uses`, as a change to `quantity`; nil when it can't be worked out.
    private static func subtract(_ quantity: String, _ stock: Amount, _ uses: [Amount]) -> UseUpChange? {
        guard let stockUnit = stock.unit else {
            if uses.contains(where: { $0.unit != nil }) { return nil }
            let left = stock.value - uses.reduce(0.0) { $0 + $1.value }
            if usedUp(left, stock.value) { return .subtract(before: quantity, after: nil) }
            guard let text = GroceryCombiner.exactly(left, metric: false, comma: stock.comma) else { return nil }
            return .subtract(before: quantity, after: replace(quantity, stock, text))
        }
        if uses.contains(where: { $0.unit == nil }) { return nil }

        // One family: exact arithmetic, in the family's smallest unit.
        if let family = GroceryCombiner.sizeOf(stockUnit)?.family,
           uses.allSatisfy({ GroceryCombiner.sizeOf($0.unit!)?.family == family }) {
            func size(_ unit: MeasureUnit) -> Double { GroceryCombiner.sizeOf(unit)!.size }
            let total = stock.value * size(stockUnit)
            let left = total - uses.reduce(0.0) { $0 + $1.value * size($1.unit!) }
            if usedUp(left, total) { return .subtract(before: quantity, after: nil) }
            if let text = exact(left, family, stock, uses) {
                return .subtract(before: quantity, after: replace(quantity, stock, text))
            }
            return rounded(quantity, stock, left * stockUnit.base / size(stockUnit))
        }

        // Across families, or through a density: grams or millilitres.
        let kind = stockUnit.kind
        let total = stock.value * stockUnit.base
        var used = 0.0
        for use in uses {
            guard let b = base(use, kind, fallback: stock.density) else { return nil }
            used += b
        }
        let left = total - used
        if usedUp(left, total) { return .subtract(before: quantity, after: nil) }
        return rounded(quantity, stock, left)
    }

    /// `left` (in `family`'s smallest unit) written exactly: in the stock's unit, else a unit a
    /// line used (largest first), else g or ml; nil when none shows it exactly.
    private static func exact(_ left: Double, _ family: GroceryCombiner.Family, _ stock: Amount, _ uses: [Amount]) -> String? {
        let metricBase: MeasureUnit? = family == .metricWeight ? .g : family == .metricVolume ? .ml : nil
        var candidates: [MeasureUnit] = [stock.unit!]
        let used = uses.compactMap(\.unit).sorted { GroceryCombiner.sizeOf($0)!.size > GroceryCombiner.sizeOf($1)!.size }
        for unit in used + (metricBase.map { [$0] } ?? []) where !candidates.contains(unit) { candidates.append(unit) }
        for unit in candidates {
            let value = left / GroceryCombiner.sizeOf(unit)!.size
            // "700 g" rather than "0.7 kg"; a fraction below one ("1/2 lb") reads fine.
            if unit.metric && value < 1 && unit != candidates.last { continue }
            guard let number = GroceryCombiner.exactly(value, metric: unit.metric, comma: stock.comma) else { continue }
            return "\(number) \(unitText(unit, value, stock, uses))"
        }
        return nil
    }

    // The unit as the quantity or a line wrote it, one agreeing in number with `value` ("cup"
    // for 1, "cups" for 2) when there is one; g or ml when neither wrote it.
    private static func unitText(_ unit: MeasureUnit, _ value: Double, _ stock: Amount, _ uses: [Amount]) -> String {
        let written = ([stock] + uses).filter { $0.unit == unit }
        guard let first = written.first else { return unit == .g ? "g" : "ml" }
        return (written.first { ($0.value > 1.0) == (value > 1.0) } ?? first).unitText
    }

    /// `left` grams or millilitres written as the converter writes them in the stock's system:
    /// g/kg or ml/L for a metric quantity, oz/lb for an imperial weight. Too little to show is
    /// used up; an imperial volume can't be written without an exact figure, so it asks (nil).
    private static func rounded(_ quantity: String, _ stock: Amount, _ left: Double) -> UseUpChange? {
        let unit = stock.unit!
        let text: String?
        if unit.metric && unit.kind == .weight {
            text = UnitConverter.metricText(left, nil, "", small: "g", large: "kg")
        } else if unit.metric {
            text = UnitConverter.metricText(left, nil, "", small: "ml", large: "L")
        } else if unit == .oz || unit == .lb {
            text = UnitConverter.ounceText(left, nil, "")
        } else {
            return nil
        }
        guard let text else { return .subtract(before: quantity, after: nil) }
        return .subtract(before: quantity, after: replace(quantity, stock, IngredientScaler.withSeparator(text, comma: stock.comma)))
    }

    /// One line's amount in grams (`.weight`) or millilitres, or nil if it can't be. A volume is
    /// in millilitres as Metric shows it (a cup is 240 ml), so the pantry agrees with the recipe's
    /// Metric view.
    private static func base(_ use: Amount, _ kind: MeasureKind, fallback: Density?) -> Double? {
        let unit = use.unit!
        if unit.kind == kind {
            if kind == .volume {
                guard let ml = UnitConverter.kitchenMlOf(unit) else { return nil }
                return use.value * ml
            }
            return use.value * unit.base
        }
        // The line's own second measure first: "1 cup (120 g) flour" is 120 g.
        if let site = use.site {
            let effective: MeasureUnit = site.unit == .oz && use.density?.liquid == true ? .flOz : site.unit
            if effective.kind == kind { return site.quantity * effective.base }
        }
        guard let gramsPerCup = (use.density ?? fallback)?.gramsPerCup else { return nil }
        let gramsPerMl = gramsPerCup / MeasureUnit.cup.base
        switch kind {
        case .weight: return use.value * unit.base * gramsPerMl
        case .volume: return use.value * unit.base / gramsPerMl
        case .none: return nil
        }
    }

    private static func replace(_ quantity: String, _ stock: Amount, _ text: String) -> String {
        quantity.u16Substring(0, stock.start) + text + quantity.u16Substring(from: stock.end)
    }
}
