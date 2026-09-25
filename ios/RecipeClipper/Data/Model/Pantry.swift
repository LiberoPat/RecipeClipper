import Foundation

/// One thing in the pantry (#51; Android's `PantryItem`). `name` is what the user typed (or the
/// ingredient name of a grocery line they ticked off), read with `language`'s words (nil: none,
/// so it never matches). `quantity` is free text as written, never read as a number.
/// `alwaysHave` marks a staple: it is never on a Buy list. The days are epoch days (`PlanDays`).
struct PantryItem: Equatable, Identifiable {
    let id: Int64
    var name: String
    var quantity: String?
    let language: String?
    var aisle: Aisle
    var inStock: Bool
    var alwaysHave: Bool
    var purchasedDay: Int64?
    var expiresDay: Int64?
}

/// A new pantry item, before it has an id. It starts in stock, bought on `purchasedDay`.
struct NewPantryItem: Equatable {
    let name: String
    let language: String?
    var aisle: Aisle? = nil
    var quantity: String? = nil
    var purchasedDay: Int64? = nil
}

/// What the edit sheet can change.
struct PantryEdit: Equatable {
    let name: String
    let quantity: String?
    let alwaysHave: Bool
    let expiresDay: Int64?
}

/// The expiry badge: no notifications, just a word on the row.
enum ExpiryBadge: Equatable { case expired, soon }

enum PantrySort: Equatable { case aisle, expiry }

/// The pantry as shown: one section per aisle, or (by expiry) one section with no aisle.
struct PantrySection: Equatable {
    let aisle: Aisle?
    let items: [PantryItem]
}

/// Sorting, searching and the expiry badge (Android's `PantryList`). Pure.
enum PantryList {

    /// "Soon" is today and the next `soonDays` days.
    static let soonDays: Int64 = 3

    static func badge(_ expiresDay: Int64?, today: Int64) -> ExpiryBadge? {
        guard let day = expiresDay else { return nil }
        if day < today { return .expired }
        if day <= today + soonDays { return .soon }
        return nil
    }

    /// `items` matching `query` (a case-insensitive part of the name; blank matches all),
    /// sorted by aisle (aisles in `Aisle` order, names A–Z within), or by expiry (soonest first,
    /// then undated, A–Z).
    static func arrange(_ items: [PantryItem], query: String, sort: PantrySort) -> [PantrySection] {
        let q = query.kTrimmed.lowercased()
        let found = items.filter { q.isEmpty || $0.name.lowercased().contains(q) }
        if found.isEmpty { return [] }
        let byName: (PantryItem, PantryItem) -> Bool = { a, b in
            let x = a.name.lowercased(), y = b.name.lowercased()
            return x != y ? x < y : a.id < b.id
        }
        switch sort {
        case .aisle:
            let order = Aisle.allCases
            return order.compactMap { aisle in
                let inAisle = found.filter { $0.aisle == aisle }
                return inAisle.isEmpty ? nil : PantrySection(aisle: aisle, items: inAisle.sorted(by: byName))
            }
        case .expiry:
            return [PantrySection(aisle: nil, items: found.sorted { a, b in
                switch (a.expiresDay, b.expiresDay) {
                case let (x?, y?): return x != y ? x < y : byName(a, b)
                case (.some, nil): return true
                case (nil, .some): return false
                case (nil, nil): return byName(a, b)
                }
            })]
        }
    }

    /// The item already here with this name (trimmed, case-insensitive) in `language`, if any.
    static func sameName(_ items: [PantryItem], name: String, language: String?) -> PantryItem? {
        let key = name.kTrimmed.lowercased()
        return items.first { $0.language == language && $0.name.kTrimmed.lowercased() == key }
    }
}

/// Have or Buy, for one ingredient of the week (#51).
enum NeedStatus: Equatable {
    /// An in-stock pantry item has this name. Presence only: "you have flour", never "enough".
    case have
    /// A staple ("always have"): never on the Buy list, in stock or not.
    case staple
    case buy
}

/// One planned recipe's line, as the reading view renders it at the planned servings.
struct NeedLine: Equatable {
    let text: String
    let recipeId: Int64
    let title: String
    let day: Int64?
    let language: String?
}

/// One ingredient of the week: every line naming it (exactly the same `IngredientName`, in the
/// same language), and whether the pantry has it. `name` is nil for a line the app can't name:
/// it stands alone and is always Buy. `pantryName` is the matched pantry item's name.
struct NeedRow: Equatable {
    let name: String?
    let lines: [NeedLine]
    let status: NeedStatus
    let pantryName: String?
}

struct WeekNeeds: Equatable {
    let buy: [NeedRow]
    let have: [NeedRow]
    var isEmpty: Bool { buy.isEmpty && have.isEmpty }
}

/// The pantry against a recipe's lines (#51; Android's `PantryMatch`). A line's name
/// (`IngredientName.of`) matches a pantry item's by `IngredientName.matches`, the density
/// table's end-of-name rule, and only in the same language. It answers "is it there", never
/// "is there enough".
enum PantryMatch {

    /// The pantry item tracking `name` in `language`, or nil: a matching staple first, else one
    /// in stock, else one that's out.
    static func find(_ name: String, language: String?, pantry: [PantryItem]) -> PantryItem? {
        guard let words = LanguageWords.forTag(language) else { return nil }
        let matching = pantry.filter { $0.language == words.language && IngredientName.matches(name, $0.name, words: words) }
        return matching.first { $0.alwaysHave } ?? matching.first { $0.inStock } ?? matching.first
    }

    static func status(_ item: PantryItem?) -> NeedStatus {
        guard let item else { return .buy }
        if item.alwaysHave { return .staple }
        return item.inStock ? .have : .buy
    }

    /// True when `line` needn't be bought: its ingredient is in stock, or a staple.
    static func covered(_ line: String, language: String?, pantry: [PantryItem]) -> Bool {
        guard let words = LanguageWords.forTag(language), let name = IngredientName.of(line, words: words) else { return false }
        return status(find(name, language: words.language, pantry: pantry)) != .buy
    }

    /// The week's "What I need": every planned recipe's lines (`sources`, already rendered),
    /// grouped by ingredient in the order first met, split into Buy and Have. Staples are with
    /// Have, never Buy.
    static func weekNeeds(_ sources: [GrocerySource], pantry: [PantryItem]) -> WeekNeeds {
        var keys: [String] = []
        var groups: [String: [NeedLine]] = [:]
        var names: [String: String] = [:]
        var unnamed = 0
        for source in sources {
            let words = LanguageWords.forTag(source.language)
            for text in source.lines {
                let line = NeedLine(text: text, recipeId: source.recipeId, title: source.title, day: source.day, language: source.language)
                let name = words.flatMap { IngredientName.of(text, words: $0) }
                let key: String
                if let name {
                    key = "n\u{1}\(source.language ?? "")\u{1}\(name)"
                    names[key] = name
                } else {
                    key = "u\u{1}\(unnamed)"
                    unnamed += 1
                }
                if groups[key] == nil { keys.append(key) }
                groups[key, default: []].append(line)
            }
        }
        let rows = keys.map { key -> NeedRow in
            let lines = groups[key]!
            let name = names[key]
            let item = name.flatMap { find($0, language: lines[0].language, pantry: pantry) }
            return NeedRow(name: name, lines: lines, status: status(item), pantryName: item?.name)
        }
        return WeekNeeds(buy: rows.filter { $0.status == .buy }, have: rows.filter { $0.status != .buy })
    }
}
