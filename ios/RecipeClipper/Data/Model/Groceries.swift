import Foundation

/// The aisles the grocery list is grouped by (#50), in the order it shows them. The raw value
/// is what `grocery_items.aisle` stores and what `shared/tables/<language>/aisles.json` names;
/// the words shown for each are UI strings. An unknown stored key reads as `.other`. A port of
/// the Kotlin `Aisle`.
enum Aisle: String, CaseIterable, Identifiable {
    case produce, meat, seafood, dairy, bakery, baking, grains, canned, condiments, spices, frozen, snacks, drinks, other

    var id: String { rawValue }
    var key: String { rawValue }

    static func fromKey(_ key: String?) -> Aisle { key.flatMap(Aisle.init(rawValue:)) ?? .other }
}

/// Which aisle a grocery line belongs in: its ingredient name (`IngredientName.of`) matched on
/// its end against the language's `aisles.json`, like the density table, so "unsalted butter"
/// is dairy, "peanut butter" condiments and "butter beans" canned. A line with no name, a name
/// nothing matches, or a language with no words is `.other`.
enum Aisles {

    private final class Table {
        // Longest alias first, so the most specific one wins. (No two aisles share an alias, so
        // equal lengths can't both match one name.)
        let aliases: [(alias: String, aisle: Aisle)]

        init(_ words: LanguageWords) {
            let aisles = words.table("aisles")["aisles"] as? [String: [String]] ?? [:]
            aliases = aisles
                .flatMap { key, names in names.map { (alias: $0, aisle: Aisle.fromKey(key)) } }
                .sorted { $0.alias.u16Count > $1.alias.u16Count }
        }
    }

    private static func table(_ words: LanguageWords) -> Table { words.compiled(Table.self, Table.init) }

    static func of(_ line: String, words: LanguageWords?) -> Aisle {
        guard let words, let name = IngredientName.of(line, words: words) else { return .other }
        return ofName(name, words: words)
    }

    /// The aisle for a name as `IngredientName.of` gives it.
    static func ofName(_ name: String, words: LanguageWords) -> Aisle {
        table(words).aliases.first { IngredientDensities.endsWithName(name, $0.alias, spaced: words.spaced) }?.aisle ?? .other
    }
}

/// One line on the grocery list (#50): `text` as written (a recipe's line as the reading view
/// showed it, scaled and converted, or what was typed), read with `language`'s words (nil: a
/// language the app has none for, so it is never named or combined). `recipeId` and
/// `plannedDay` say where it came from.
struct GroceryItem: Equatable, Identifiable {
    let id: Int64
    let text: String
    let language: String?
    var aisle: Aisle
    var checked: Bool
    let sortOrder: Int
    var recipeId: Int64? = nil
    var plannedDay: Int64? = nil
}

/// How the grocery list is shown: grouped by aisle, and within an aisle, lines naming the same
/// ingredient kept together. The rule behind "never a confident wrong number", ported from the
/// Kotlin `GroceryCombiner` (see there):
///
/// - Lines with the same `IngredientName` (exactly, in the same language, checked or not
///   alike) are one ingredient.
/// - They add up into one row only when every one is a single exact amount in one family of
///   units that convert exactly into each other (metric weight, imperial weight, metric volume,
///   US volume, sticks, or counts with identical words), in a unit the lines used that shows
///   the total exactly.
/// - Otherwise they sit together under their name, each as written.
enum GroceryCombiner {

    enum Row: Equatable, Identifiable {
        /// A line on its own.
        case single(GroceryItem)
        /// Several lines added up into `text`.
        case combined(name: String, text: String, items: [GroceryItem])
        /// Several lines naming `name` that can't be added up honestly, each as written.
        case together(name: String, items: [GroceryItem])

        var items: [GroceryItem] {
            switch self {
            case .single(let item): return [item]
            case .combined(_, _, let items), .together(_, let items): return items
            }
        }

        var id: Int64 { items[0].id }
    }

    struct Section: Equatable, Identifiable {
        let aisle: Aisle
        let rows: [Row]
        var id: String { aisle.key }
    }

    /// The list as shown: aisles in `Aisle` order, empty ones left out; within one, unchecked
    /// rows before checked ones, each in the order its first line was added.
    static func sections(_ items: [GroceryItem], decisions: Decisions = .none) -> [Section] {
        let sorted = items.sorted { ($0.sortOrder, $0.id) < ($1.sortOrder, $1.id) }
        return Aisle.allCases.compactMap { aisle in
            let inAisle = sorted.filter { $0.aisle == aisle }
            if inAisle.isEmpty { return nil }
            let rows = [false, true].flatMap { checked in group(inAisle.filter { $0.checked == checked }, decisions) }
            return Section(aisle: aisle, rows: rows)
        }
    }

    /// With the model's answers (#99, `GroceryDecisions`): a line whose trailing text is a note
    /// or junk is read without it, and groups whose names are definitely the same share a row,
    /// under the first group's name. Adding up keeps `combine`'s exact rules.
    private static func group(_ items: [GroceryItem], _ decisions: Decisions) -> [Row] {
        // Keyed by language and name; a line with no name is its own group.
        var order: [String] = []
        var groups: [String: [GroceryItem]] = [:]
        var names: [Int64: String] = [:]
        for item in items {
            let name = GroceryDecisions.name(item, decisions: decisions)
            if let name { names[item.id] = name }
            let key = name.map { "n\u{0}\(item.language ?? "")\u{0}\($0)" } ?? "i\u{0}\(item.id)"
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(item)
        }
        return mergeSame(order.map { groups[$0]! }, names, decisions).map { lines in
            let first = lines[0]
            guard lines.count > 1, let name = names[first.id] else { return .single(first) }
            let texts = lines.map { GroceryDecisions.effectiveText($0, decisions: decisions) }
            let sameName = lines.allSatisfy { names[$0.id] == name }
            if let words = LanguageWords.forTag(first.language),
               let total = combine(texts, words: words, requireSameName: sameName) {
                return .combined(name: name, text: total, items: lines)
            }
            return .together(name: name, items: lines)
        }
    }

    // Each group joins the first earlier one holding a name definitely the same as its own.
    private static func mergeSame(_ groups: [[GroceryItem]], _ names: [Int64: String], _ decisions: Decisions) -> [[GroceryItem]] {
        var out: [[GroceryItem]] = []
        for group in groups {
            let first = group[0]
            if let name = names[first.id], let into = out.firstIndex(where: { o in
                o[0].language == first.language && o.contains { names[$0.id].map { decisions.sameGrocery($0, name, language: first.language) } ?? false }
            }) {
                out[into] += group
            } else {
                out.append(group)
            }
        }
        return out
    }

    // MARK: - Adding up

    /// Units that convert exactly into each other.
    private enum Family { case metricWeight, imperialWeight, metricVolume, usVolume, stick, count }

    /// Each unit's family and size in the family's smallest unit.
    private static func sizeOf(_ unit: MeasureUnit) -> (family: Family, size: Double)? {
        switch unit {
        case .g: return (.metricWeight, 1)
        case .kg: return (.metricWeight, 1000)
        case .oz: return (.imperialWeight, 1)
        case .lb: return (.imperialWeight, 16)
        case .ml: return (.metricVolume, 1)
        case .cl: return (.metricVolume, 10)
        case .dl: return (.metricVolume, 100)
        case .l: return (.metricVolume, 1000)
        case .cup200: return (.metricVolume, 200)
        case .riceCup: return (.metricVolume, 180)
        case .tsp: return (.usVolume, 1)
        case .tbsp: return (.usVolume, 3)
        case .flOz: return (.usVolume, 6)
        case .cup: return (.usVolume, 48)
        case .stick: return (.stick, 1)
        case .varies: return nil
        }
    }

    /// One line's amount: `value` in its family's smallest unit (`unitText` as written), or a
    /// count of `rest`.
    private struct Amount {
        let family: Family
        let value: Double
        let unit: MeasureUnit?
        let unitText: String
        let rest: String
    }

    private static let whitespace = JRegex(#"\s+"#)

    private static func amount(_ line: String, _ words: LanguageWords) -> Amount? {
        let p = IngredientScaler.patterns(words)
        if p.amountAfterName || p.unreadable(line) { return nil }
        let c = UnitConverter.patterns(words)
        guard let lead = p.leading.find(line) else { return nil }
        if !lead[4].isEmpty { return nil } // a range is no one figure
        let rest = line.u16Substring(from: lead.end)
        if p.notAnAmount.containsMatch(in: rest) { return nil }
        guard let value = p.parse(lead[2]), value > 0 else { return nil }

        guard let unitMatch = c.unitAtStart.find(rest) else {
            // A count: "2 eggs". Only the very same words add up, and never a package size.
            let tail = rest.kTrimmed
            guard let first = tail.first, first.isLetter, !tail.contains("("), !tail.contains("/") else { return nil }
            return Amount(family: .count, value: value, unit: nil, unitText: "", rest: tail)
        }
        guard let unit = MeasureUnit.fromText(unitMatch[1], words: words), let (family, size) = sizeOf(unit) else {
            return nil
        }
        let after = rest.u16Substring(from: unitMatch.end)
        // "1 cup plus 2 tbsp", "1 cup (120 g)", "1 cup/120 g": more than one figure.
        if c.continuationAtStart.containsMatch(in: after) { return nil }
        let next = after.drop { $0.isWhitespace }
        if next.hasPrefix("(") || next.hasPrefix("/") { return nil }
        return Amount(family: family, value: value * size, unit: unit, unitText: unitMatch[1].kTrimmed, rest: after.kTrimmed)
    }

    /// The lines added up as one line ("300 g flour"), or nil when they can't be added up
    /// exactly, or don't all name the same ingredient in `words`' language (not checked when
    /// `requireSameName` is false: the model decided the names are the same, #99). The words
    /// after the total are the shortest any line wrote after its unit, as written.
    static func combine(_ lines: [String], words: LanguageWords, requireSameName: Bool = true) -> String? {
        guard lines.count >= 2 else { return nil }
        if requireSameName {
            guard let name = IngredientName.of(lines[0], words: words) else { return nil }
            if lines.contains(where: { IngredientName.of($0, words: words) != name }) { return nil }
        }
        var amounts: [Amount] = []
        for line in lines {
            guard let a = amount(line, words) else { return nil }
            amounts.append(a)
        }
        let family = amounts[0].family
        if amounts.contains(where: { $0.family != family }) { return nil }
        let comma = lines.contains { IngredientScaler.decimalComma.containsMatch(in: $0) }
        let total = amounts.reduce(0.0) { $0 + $1.value }

        if family == .count {
            let rest = amounts[0].rest
            if amounts.contains(where: { normalize($0.rest) != normalize(rest) }) { return nil }
            guard let text = exactly(total, metric: false, comma: comma) else { return nil }
            return "\(text) \(rest)"
        }

        // The largest unit the lines used that shows the total exactly.
        var units: [MeasureUnit] = []
        for a in amounts { if let u = a.unit, !units.contains(u) { units.append(u) } }
        units.sort { sizeOf($0)!.size > sizeOf($1)!.size }
        var rest = amounts[0].rest
        for a in amounts where a.rest.u16Count < rest.u16Count { rest = a.rest }
        for unit in units {
            let value = total / sizeOf(unit)!.size
            guard let text = exactly(value, metric: unit.metric, comma: comma) else { continue }
            return "\(text) \(unitText(amounts, unit, value)) \(rest)"
        }
        return nil
    }

    /// `value` formatted as the scaler writes amounts, or nil if that would round it.
    private static func exactly(_ value: Double, metric: Bool, comma: Bool) -> String? {
        let plain = metric ? IngredientScaler.formatMetric(value) : IngredientScaler.format(value)
        guard let shown = IngredientScaler.parse(plain) else { return nil }
        if abs(shown - value) > 1e-9 * max(1.0, abs(value)) { return nil }
        return IngredientScaler.withSeparator(plain, comma: comma)
    }

    // The unit as one of the lines wrote it: one whose amount agrees in number with the total
    // ("cups" for 3, "cup" for 1), else the first line in that unit.
    private static func unitText(_ amounts: [Amount], _ unit: MeasureUnit, _ total: Double) -> String {
        let size = sizeOf(unit)!.size
        let same = amounts.filter { $0.unit == unit }
        return (same.first { ($0.value / size > 1.0) == (total > 1.0) } ?? same[0]).unitText
    }

    /// A count's words, collapsed and lowercase, for comparing counts.
    private static func normalize(_ text: String) -> String { whitespace.replace(text.kTrimmed, with: " ").lowercased() }
}

/// The grocery list as plain text for sharing (#50): the title, then each aisle's name and its
/// unchecked rows, as shown. Checked items are left out: they're already in the basket.
enum GroceryShareText {

    static func format(_ sections: [GroceryCombiner.Section], title: String, aisleName: (Aisle) -> String) -> String {
        var lines = [title]
        for section in sections {
            let rows = section.rows.filter { row in !row.items.contains { $0.checked } }
            if rows.isEmpty { continue }
            lines.append("")
            lines.append(aisleName(section.aisle))
            for row in rows {
                switch row {
                case .single(let item): lines.append("- \(item.text)")
                case .combined(_, let text, _): lines.append("- \(text)")
                case .together(_, let items): lines += items.map { "- \($0.text)" }
                }
            }
        }
        return lines.joined(separator: "\n")
    }
}

/// A line to put on the list, and where it came from. `language` is the tag its words are read with.
struct NewGroceryLine: Equatable {
    let text: String
    let language: String?
    var recipeId: Int64? = nil
    var plannedDay: Int64? = nil
}

/// A recipe planned on `day` at `servings` (nil: its own yield), with what it needs.
struct PlannedIngredients: Equatable {
    let entryId: Int64
    let day: Int64
    let servings: Int?
    let recipeId: Int64
    let title: String
    let ingredients: [String]
    let yield: String?
    let language: String?
}

/// One recipe's lines in the "Add to groceries" sheet: `lines` as the reading view shows them,
/// and from the week, the `day` it's planned on. `key` tells two plannings of one recipe apart.
struct GrocerySource: Equatable, Identifiable {
    let key: String
    let recipeId: Int64
    let title: String
    let day: Int64?
    let language: String?
    let lines: [String]
    var id: String { key }
}

/// Builds the add sheet's sources (#50). Pure: lines in, lines out.
enum GrocerySources {

    /// A blank line or a heading ("For the sauce:") is nothing to buy, so it isn't offered.
    static func buyable(_ line: String) -> Bool { !line.kIsBlank && !line.kTrimmed.hasSuffix(":") }

    /// A recipe on the reading view: `rendered` are its lines exactly as shown there.
    static func fromRecipe(recipeId: Int64, title: String, language: String?, rendered: [String]) -> GrocerySource {
        GrocerySource(
            key: "recipe-\(recipeId)", recipeId: recipeId, title: title, day: nil, language: language,
            lines: rendered.filter(buyable)
        )
    }

    /// The week's planned recipes, each rendered as the reading view would show it at its
    /// planned servings. A recipe with no usable yield, or no planned servings, shows as written.
    /// `decisions`: count brackets already decided (#104), as the reading view applies them.
    static func fromPlan(
        _ planned: [PlannedIngredients], system: UnitSystem, convertLiquids: Bool, decisions: Decisions = .none
    ) -> [GrocerySource] {
        planned.compactMap { p in
            let tag = p.language ?? LanguageWords.resolve(declared: nil, page: nil) {
                LanguageWords.detectionText(name: p.title, ingredients: p.ingredients)
            }
            let words = LanguageWords.forTag(tag)
            let base = Servings.parse(p.yield, words: words)
            let factor = (base != nil && p.servings != nil) ? Double(p.servings!) / Double(base!) : 1.0
            let lines = IngredientRendering.render(
                p.ingredients, factor: factor, system: system, convertLiquids: convertLiquids, words: words,
                decisions: decisions
            ).filter(buyable)
            if lines.isEmpty { return nil }
            return GrocerySource(
                key: "plan-\(p.entryId)", recipeId: p.recipeId, title: p.title, day: p.day, language: words?.language,
                lines: lines
            )
        }
    }
}
