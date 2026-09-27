import Foundation

/// Where a shared file's pantry items go (#149): the receiver's choice.
enum PantryDestination: String, Equatable {
    case pantry = "PANTRY"
    case groceries = "GROCERIES"
}

/// What the receiver ticked in a shared file: the file ids of each section, and where its
/// pantry items go.
struct ShareChoice: Equatable {
    var recipeIds: Set<String>
    var groceryIds: Set<String>
    var pantryIds: Set<String> = []
    var pantryTo: PantryDestination = .pantry
}

/// A small file that carries picked recipes and grocery or pantry items to someone else's Recipe
/// Clipper (#149, phase 2; Android's ShareFile). It is the export format (#26, `BackupJson`)
/// with `"kind": "share"` and only what was picked: no lists, plan, menus or photos. Pure, like
/// `BackupMerger`; both platforms read `shared/fixtures/backup/share-v1.recipeclipper`.
///
/// - **Sent:** each recipe complete, as saved (not scaled or converted), without what is the
///   sender's own: no ticks and no note (the text share leaves the note out too). Grocery items
///   as stored, unticked, each naming its recipe only if that recipe is in the file.
/// - **Received:** what the receiver ticked, merged by `BackupMerger` like an import. A recipe
///   arrives as the newest viewed, like a shared link, with no ticks; a grocery item keeps its
///   recipe only if that recipe was ticked too; pantry items go to the Pantry or, as their
///   names, onto the grocery list.
enum ShareFile {
    /// The file's extension, and the types Android and iOS open it by.
    static let fileExtension = "recipeclipper"
    static let mimeType = "application/vnd.recipeclipper+json"
    /// The exported UTType (Info.plist `UTExportedTypeDeclarations`).
    static let typeIdentifier = "com.liberopat.recipeclipper.share"

    /// The file to send: `recipes` complete, `groceries` unticked, `pantry` as it is.
    static func make(
        now: Int64,
        recipes: [BackupRecipe],
        groceries: [BackupGroceryItem] = [],
        pantry: [BackupPantryItem] = []
    ) -> Backup {
        var seen = Set<String>()
        let sent = recipes.filter { seen.insert($0.id).inserted }.map { recipe -> BackupRecipe in
            var r = recipe
            r.lastViewedAt = now
            r.checkedIngredients = []
            r.notes = nil
            return r
        }
        let ids = Set(sent.map(\.id))
        var seenGroceries = Set<String>()
        let items = groceries.filter { seenGroceries.insert($0.id).inserted }.map { item -> BackupGroceryItem in
            var g = item
            g.checked = false
            g.plannedDay = nil
            if let recipe = g.recipeId, !ids.contains(recipe) { g.recipeId = nil }
            return g
        }
        var seenPantry = Set<String>()
        return Backup(
            exportedAt: now, recipes: sent, lists: [], memberships: [],
            pantry: pantry.filter { seenPantry.insert($0.id).inserted },
            groceries: items,
            isShare: true
        )
    }

    /// The Pantry's file (#149): its in-stock items, as they are, and nothing else; nil when none
    /// is in stock. Items that are out are left out, as in the Pantry's "Send list": running out
    /// put them on the grocery list (#146), which Groceries sends.
    static func pantry(now: Int64, items: [BackupPantryItem]) -> Backup? {
        let inStock = items.filter(\.inStock)
        return inStock.isEmpty ? nil : make(now: now, recipes: [], pantry: inStock)
    }

    /// What to merge once the receiver has chosen: only the ticked parts of `file`, each new to
    /// this phone as of `now`. A pantry item sent onto the grocery list keeps its uid, so the
    /// same file opened twice adds it once, as every other item.
    static func chosen(_ file: Backup, _ choice: ShareChoice, now: Int64) -> Backup {
        let recipes = file.recipes.filter { choice.recipeIds.contains($0.id) }.map { recipe -> BackupRecipe in
            var r = recipe
            r.lastViewedAt = now
            r.checkedIngredients = []
            return r
        }
        let kept = Set(recipes.map(\.id))
        let groceries = file.groceries.filter { choice.groceryIds.contains($0.id) }.map { item -> BackupGroceryItem in
            var g = item
            g.checked = false
            g.plannedDay = nil
            if let recipe = g.recipeId, !kept.contains(recipe) { g.recipeId = nil }
            g.updatedAt = now
            return g
        }
        let pantry = file.pantry.filter { choice.pantryIds.contains($0.id) }
        let toPantry = choice.pantryTo == .pantry
        return Backup(
            exportedAt: now, recipes: recipes, lists: [], memberships: [],
            pantry: toPantry ? pantry.map { var p = $0; p.updatedAt = now; return p } : [],
            groceries: toPantry ? groceries : groceries + pantry.map {
                BackupGroceryItem(
                    id: $0.id, text: $0.name, language: $0.language, aisle: $0.aisle, checked: false,
                    recipeId: nil, plannedDay: nil, updatedAt: now
                )
            },
            isShare: true
        )
    }

    /// "Sheet-pan chicken.recipeclipper": the title, safe as a file name on any phone.
    static func fileName(_ title: String) -> String {
        let forbidden = Set("/\\:*?\"<>|")
        let replaced = String(title.map { forbidden.contains($0) || $0.isControl ? " " : $0 })
        var safe = replaced.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        while safe.hasPrefix(".") { safe.removeFirst() }
        safe = String(safe.prefix(maxName)).trimmingCharacters(in: .whitespaces)
        return (safe.isEmpty ? "Recipe Clipper" : safe) + "." + fileExtension
    }

    private static let maxName = 60
}

private extension Character {
    /// ISO control characters, as Kotlin's `isISOControl`.
    var isControl: Bool { unicodeScalars.allSatisfy { $0.properties.generalCategory == .control } }
}
