import Foundation

/// One thing in the pantry (#51; Android's `PantryItem`). `name` is what the user typed (or the
/// ingredient name of a grocery line they ticked off), read with `language`'s words (nil: none,
/// so it never matches). `quantity` is free text as written, never read as a number.
/// `alwaysHave` marks a staple: it is never on a Buy list. The days are epoch days (`PlanDays`).
/// `inStock` and `runningLow` make its `stock` (#194): running low is still in stock, so everything
/// that asks "is it there" reads `inStock`.
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
    var runningLow: Bool = false

    var stock: PantryStock {
        if !inStock { return .runOut }
        return runningLow ? .runningLow : .inStock
    }
}

/// A pantry item's three states (#194; Android's `PantryStock`). In stock and running low both
/// count as having it (presence only, #51); running low and run out both put its name on the
/// grocery list.
enum PantryStock: CaseIterable, Equatable { case inStock, runningLow, runOut }

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

/// The pantry as shown: one section per aisle, or (by expiry) one section with no aisle, then
/// `runOut`: the items that have run out, in their own section at the bottom (#194).
struct PantrySection: Equatable {
    let aisle: Aisle?
    let items: [PantryItem]
    var runOut: Bool = false
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
    /// then undated, A–Z). Items that have run out are left out of those and follow in one last
    /// section (`runOut`), in the same order (#194).
    static func arrange(_ items: [PantryItem], query: String, sort: PantrySort) -> [PantrySection] {
        let q = query.kTrimmed.lowercased()
        let found = items.filter { q.isEmpty || $0.name.lowercased().contains(q) }
        if found.isEmpty { return [] }
        let byName: (PantryItem, PantryItem) -> Bool = { a, b in
            let x = a.name.lowercased(), y = b.name.lowercased()
            return x != y ? x < y : a.id < b.id
        }
        let byExpiry: (PantryItem, PantryItem) -> Bool = { a, b in
            switch (a.expiresDay, b.expiresDay) {
            case let (x?, y?): return x != y ? x < y : byName(a, b)
            case (.some, nil): return true
            case (nil, .some): return false
            case (nil, nil): return byName(a, b)
            }
        }
        let order = Aisle.allCases
        let byAisle: (PantryItem, PantryItem) -> Bool = { a, b in
            let x = order.firstIndex(of: a.aisle)!, y = order.firstIndex(of: b.aisle)!
            return x != y ? x < y : byName(a, b)
        }
        let have = found.filter(\.inStock)
        let out = found.filter { !$0.inStock }
        var sections: [PantrySection]
        switch sort {
        case .aisle:
            sections = order.compactMap { aisle in
                let inAisle = have.filter { $0.aisle == aisle }
                return inAisle.isEmpty ? nil : PantrySection(aisle: aisle, items: inAisle.sorted(by: byName))
            }
        case .expiry:
            sections = have.isEmpty ? [] : [PantrySection(aisle: nil, items: have.sorted(by: byExpiry))]
        }
        if !out.isEmpty {
            sections.append(PantrySection(aisle: nil, items: out.sorted(by: sort == .aisle ? byAisle : byExpiry), runOut: true))
        }
        return sections
    }

    /// The unticked grocery lines that are `item` itself: its name as the pantry puts it there
    /// (trimmed, case-insensitive, a listed pair's number aside (`IngredientName.same`), in its
    /// language). Running out adds that line only when there is none (#146), and using up (#147)
    /// the same. A recipe's "2 cups flour" isn't one: the basket tag reads `onListLines`.
    static func ownLines(_ item: PantryItem, _ groceries: [GroceryItem]) -> [GroceryItem] {
        let words = LanguageWords.forTag(item.language)
        let name = IngredientName.key(item.name, words: words)
        return groceries.filter { !$0.checked && $0.language == item.language && IngredientName.key($0.text, words: words) == name }
    }

    /// The unticked grocery lines that put `item` on the list, which is what the basket tag means
    /// and what tapping it removes (owner, 2026-09-29): every line in its language whose
    /// ingredient's name matches the item's as What I need matches them (`IngredientName.matches`:
    /// a typed "2 onions" and a recipe's "1 onion, sliced" are "onions", "red onion" isn't), and
    /// its own line (`ownLines`) whatever that reads as.
    static func onListLines(_ item: PantryItem, _ groceries: [GroceryItem]) -> [GroceryItem] {
        let words = LanguageWords.forTag(item.language)
        let own = IngredientName.key(item.name, words: words)
        return groceries.filter { line in
            guard !line.checked, line.language == item.language else { return false }
            if IngredientName.key(line.text, words: words) == own { return true }
            guard let words, let name = IngredientName.of(line.text, words: words) else { return false }
            return IngredientName.matches(name, item.name, words: words)
        }
    }

    /// The item already here with this name (trimmed, case-insensitive, a listed pair's number
    /// aside: "onion" finds "Onions") in `language`, if any.
    static func sameName(_ items: [PantryItem], name: String, language: String?) -> PantryItem? {
        let words = LanguageWords.forTag(language)
        let key = IngredientName.key(name, words: words)
        return items.first { $0.language == language && IngredientName.key($0.name, words: words) == key }
    }
}

/// The pantry as plain text for sending (#149; Android's PantryShareText): what's in stock, for
/// someone at the shops to see what's at home. The title, then each section as the screen
/// arranges it (an aisle's name, or no heading when sorted by expiry) and its in-stock items,
/// one per line after "- ": the name, then the quantity as written in brackets ("- basmati rice
/// (half a bag)"). Items that are out are left out: running out already put them on the grocery
/// list (#146), which sends its own. `aisleName` and `title` are the screen's words.
enum PantryShareText {

    static func format(_ sections: [PantrySection], title: String, aisleName: (Aisle) -> String) -> String {
        var lines = [title]
        for section in sections {
            let items = section.items.filter(\.inStock)
            if items.isEmpty { continue }
            lines.append("")
            if let aisle = section.aisle { lines.append(aisleName(aisle)) }
            for item in items {
                let quantity = (item.quantity ?? "").kTrimmed
                lines.append(quantity.isEmpty ? "- \(item.name.kTrimmed)" : "- \(item.name.kTrimmed) (\(quantity))")
            }
        }
        return lines.joined(separator: "\n")
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

/// One ingredient of the week: every line naming it (exactly the same `IngredientName`, a listed
/// pair's number aside, in the same language), and whether the pantry has it. `name` is the first
/// line's; it is nil for a line the app can't name: it stands alone and is always Buy.
/// `pantryName` is the matched pantry item's name.
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
/// (`IngredientName.of`) matches a pantry item's by `IngredientName.matches` ("unsalted butter"
/// is "butter"; "rice flour" is never "flour", either way), and only in the same language. It
/// answers "is it there", never "is there enough".
enum PantryMatch {

    /// The pantry item tracking `name` in `language`, or nil: a matching staple first, else one
    /// in stock, else one that's out.
    /// Only when no name matches, a staple or in-stock item the model said is the same (#104,
    /// `decisions`) counts: its "same" can only turn Buy into Have, never the other way.
    static func find(_ name: String, language: String?, pantry: [PantryItem], decisions: Decisions = .none) -> PantryItem? {
        guard let words = LanguageWords.forTag(language) else { return nil }
        let sameLanguage = pantry.filter { $0.language == words.language }
        let matching = sameLanguage.filter { IngredientName.matches(name, $0.name, words: words) }
        if !matching.isEmpty {
            return matching.first { $0.alwaysHave } ?? matching.first { $0.inStock } ?? matching.first
        }
        let same = sameLanguage.filter {
            ($0.alwaysHave || $0.inStock) && decisions.sameIngredient(name, $0.name, language: words.language)
        }
        return same.first { $0.alwaysHave } ?? same.first
    }

    static func status(_ item: PantryItem?) -> NeedStatus {
        guard let item else { return .buy }
        if item.alwaysHave { return .staple }
        return item.inStock ? .have : .buy
    }

    /// True when `line` needn't be bought: its ingredient is in stock, or a staple.
    static func covered(_ line: String, language: String?, pantry: [PantryItem], decisions: Decisions = .none) -> Bool {
        guard let words = LanguageWords.forTag(language), let name = IngredientName.of(line, words: words) else { return false }
        return status(find(name, language: words.language, pantry: pantry, decisions: decisions)) != .buy
    }

    /// The week's "What I need": every planned recipe's lines (`sources`, already rendered),
    /// grouped by ingredient in the order first met, split into Buy and Have. Staples are with
    /// Have, never Buy.
    static func weekNeeds(_ sources: [GrocerySource], pantry: [PantryItem], decisions: Decisions = .none) -> WeekNeeds {
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
                    // One ingredient whatever the number of a listed pair's words ("onion", "onions").
                    key = "n\u{1}\(source.language ?? "")\u{1}\(IngredientName.key(name, words: words))"
                    if names[key] == nil { names[key] = name }
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
            let item = name.flatMap { find($0, language: lines[0].language, pantry: pantry, decisions: decisions) }
            return NeedRow(name: name, lines: lines, status: status(item), pantryName: item?.name)
        }
        return WeekNeeds(buy: rows.filter { $0.status == .buy }, have: rows.filter { $0.status != .buy })
    }
}
