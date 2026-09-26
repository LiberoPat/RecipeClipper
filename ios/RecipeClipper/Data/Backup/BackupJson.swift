import Foundation

/// The export file's JSON, both ways (Android's BackupJson). Pure: text in, data out.
///
/// ```
/// { "format": "recipe-clipper-backup", "formatVersion": 1, "exportedAt": <epoch ms>,
///   "recipes":     [{ "id", "sourceUrl", "sourceType", "title", "imageUrl", "ingredients",
///                     "instructions", "prepTime", "cookTime", "totalTime", "servings",
///                     "lastViewedAt", "checkedIngredients", "notes", "language",
///                     "contentOrigin", "editedAt" }],
///   "lists":       [{ "id", "name", "isFavorites", "isBuiltIn", "sortOrder", "createdAt" }],
///   "memberships": [{ "recipeId", "listId", "addedAt" }],
///   "pantry":      [{ "id", "name", "quantity", "language", "aisle", "inStock", "alwaysHave",
///                     "purchasedDay", "expiresDay", "updatedAt" }],
///   "groceries":   [{ "id", "text", "language", "aisle", "checked", "recipeId", "plannedDay",
///                     "updatedAt" }],
///   "mealTypes":   [{ "id", "name", "builtInKey", "sortOrder", "updatedAt" }],
///   "mealPlan":    [{ "id", "day", "mealTypeId", "recipeId", "servings", "note", "sortOrder",
///                     "updatedAt" }],
///   "menus":       [{ "id", "name", "updatedAt" }],
///   "menuEntries": [{ "id", "menuId", "dayOffset", "mealTypeId", "recipeId", "servings", "note",
///                     "sortOrder", "updatedAt" }] }
/// ```
///
/// `menus` and `menuEntries` (#52) came the same way; a menu meal reads like a planned one, and
/// one whose `menuId` names no menu in the file is malformed.
///
/// `pantry` (#51), `groceries` (#50), `mealTypes` and `mealPlan` (#49) came later without a
/// version bump: an older reader ignores them. A grocery's or a planned meal's `recipeId`
/// naming no recipe in the file reads as none, and so does a meal's `mealTypeId` naming no
/// meal type in the file (it imports as Dinner).
///
/// Reading is strict about what it needs and lenient about the rest, so the format can grow:
/// unknown keys and sections are ignored, a missing section is empty, and a missing optional
/// field is nil (or 0, false, empty). The version is checked before anything else is read, so
/// a newer file is refused as `.newerVersion` rather than misread as `.malformed`.
///
/// JSONSerialization hands booleans back as NSNumber, so a `1` would pass for `true` (and
/// `true` for 1) with a plain `as? Bool`; the reader tells them apart by CF type, as org.json
/// does by class on Android.
enum BackupJson {

    static func encode(_ backup: Backup) -> String {
        let root: [String: Any] = [
            "format": Backup.format,
            "formatVersion": Backup.formatVersion,
            "exportedAt": NSNumber(value: backup.exportedAt),
            "recipes": backup.recipes.map(json),
            "lists": backup.lists.map(json),
            "memberships": backup.memberships.map(json),
            "pantry": backup.pantry.map(json),
            "groceries": backup.groceries.map(json),
            "mealTypes": backup.mealTypes.map(json),
            "mealPlan": backup.mealPlan.map(json),
            "menus": backup.menus.map(json),
            "menuEntries": backup.menuEntries.map(json),
        ]
        // Only when there are some (#116): an export without photos is the same file as before.
        var withPhotos = root
        if !backup.cookedPhotos.isEmpty { withPhotos["cookedPhotos"] = backup.cookedPhotos.map(json) }
        guard let data = try? JSONSerialization.data(
            withJSONObject: withPhotos, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        ) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    static func decode(_ text: String) -> Result<Backup, BackupError> {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
              let root = object as? [String: Any],
              root["format"] as? String == Backup.format
        else { return .failure(.notABackup) }

        do {
            let top = Reader(path: "")
            guard let version = try top.int(root, "formatVersion"), version >= 1 else {
                throw Malformed(path: "formatVersion")
            }
            if version > Backup.formatVersion { return .failure(.newerVersion(found: version)) }
            return .success(try readBackup(root))
        } catch let error as Malformed {
            return .failure(.malformed(error.path))
        } catch {
            return .failure(.notABackup)
        }
    }

    private static func readBackup(_ root: [String: Any]) throws -> Backup {
        let top = Reader(path: "")
        let exportedAt = try top.int64(root, "exportedAt") ?? 0

        let recipes = try top.objects(root, "recipes").map { path, o -> BackupRecipe in
            let r = Reader(path: path)
            let id = try r.requiredId(o, "id")
            guard let url = try r.string(o, "sourceUrl"), !url.isBlank else { throw Malformed(path: "\(path).sourceUrl") }
            guard let title = try r.string(o, "title") else { throw Malformed(path: "\(path).title") }
            return BackupRecipe(
                id: id,
                sourceUrl: url,
                sourceType: try r.string(o, "sourceType") ?? "BLOG",
                title: title,
                imageUrl: try r.string(o, "imageUrl"),
                ingredients: try r.strings(o, "ingredients"),
                instructions: try r.strings(o, "instructions"),
                prepTime: try r.string(o, "prepTime"),
                cookTime: try r.string(o, "cookTime"),
                totalTime: try r.string(o, "totalTime"),
                servings: try r.string(o, "servings"),
                lastViewedAt: try r.int64(o, "lastViewedAt") ?? 0,
                checkedIngredients: Set(try r.ints(o, "checkedIngredients")),
                notes: try r.string(o, "notes"),
                language: try r.string(o, "language"),
                contentOrigin: (try r.string(o, "contentOrigin")).flatMap { $0.isEmpty ? nil : $0 } ?? "PARSED",
                editedAt: try r.int64(o, "editedAt")
            )
        }
        try requireUniqueIds(recipes.map(\.id), "recipes")

        let lists = try top.objects(root, "lists").map { path, o -> BackupList in
            let r = Reader(path: path)
            let id = try r.requiredId(o, "id")
            guard let name = try r.string(o, "name"), !name.isBlank else { throw Malformed(path: "\(path).name") }
            return BackupList(
                id: id,
                name: name,
                isFavorites: try r.bool(o, "isFavorites") ?? false,
                isBuiltIn: try r.bool(o, "isBuiltIn") ?? false,
                sortOrder: try r.int(o, "sortOrder") ?? 0,
                createdAt: try r.int64(o, "createdAt") ?? 0
            )
        }
        try requireUniqueIds(lists.map(\.id), "lists")

        let recipeIds = Set(recipes.map(\.id))
        let listIds = Set(lists.map(\.id))
        let memberships = try top.objects(root, "memberships").map { path, o -> BackupMembership in
            let r = Reader(path: path)
            let membership = BackupMembership(
                recipeId: try r.requiredId(o, "recipeId"),
                listId: try r.requiredId(o, "listId"),
                addedAt: try r.int64(o, "addedAt") ?? 0
            )
            guard recipeIds.contains(membership.recipeId) else { throw Malformed(path: "\(path).recipeId") }
            guard listIds.contains(membership.listId) else { throw Malformed(path: "\(path).listId") }
            return membership
        }

        let pantry = try top.objects(root, "pantry").map { path, o -> BackupPantryItem in
            let r = Reader(path: path)
            let id = try r.requiredId(o, "id")
            guard let name = try r.string(o, "name"), !name.isBlank else { throw Malformed(path: "\(path).name") }
            return BackupPantryItem(
                id: id,
                name: name,
                quantity: try r.string(o, "quantity"),
                language: try r.string(o, "language"),
                aisle: try r.string(o, "aisle") ?? "other",
                inStock: try r.bool(o, "inStock") ?? true,
                alwaysHave: try r.bool(o, "alwaysHave") ?? false,
                purchasedDay: try r.int64(o, "purchasedDay"),
                expiresDay: try r.int64(o, "expiresDay"),
                updatedAt: try r.int64(o, "updatedAt") ?? 0
            )
        }
        try requireUniqueIds(pantry.map(\.id), "pantry")

        let groceries = try top.objects(root, "groceries").map { path, o -> BackupGroceryItem in
            let r = Reader(path: path)
            let id = try r.requiredId(o, "id")
            guard let text = try r.string(o, "text"), !text.isBlank else { throw Malformed(path: "\(path).text") }
            return BackupGroceryItem(
                id: id,
                text: text,
                language: try r.string(o, "language"),
                aisle: try r.string(o, "aisle") ?? "other",
                checked: try r.bool(o, "checked") ?? false,
                recipeId: try r.string(o, "recipeId").flatMap { recipeIds.contains($0) ? $0 : nil },
                plannedDay: try r.int64(o, "plannedDay"),
                updatedAt: try r.int64(o, "updatedAt") ?? 0
            )
        }
        try requireUniqueIds(groceries.map(\.id), "groceries")

        let mealTypes = try top.objects(root, "mealTypes").map { path, o -> BackupMealType in
            let r = Reader(path: path)
            let id = try r.requiredId(o, "id")
            guard let name = try r.string(o, "name"), !name.isBlank else { throw Malformed(path: "\(path).name") }
            return BackupMealType(
                id: id,
                name: name,
                builtInKey: try r.string(o, "builtInKey").flatMap { $0.isBlank ? nil : $0 },
                sortOrder: try r.int(o, "sortOrder") ?? 0,
                updatedAt: try r.int64(o, "updatedAt") ?? 0
            )
        }
        try requireUniqueIds(mealTypes.map(\.id), "mealTypes")

        let mealTypeIds = Set(mealTypes.map(\.id))
        let mealPlan = try top.objects(root, "mealPlan").map { path, o -> BackupPlanEntry in
            let r = Reader(path: path)
            let id = try r.requiredId(o, "id")
            guard let day = try r.int64(o, "day") else { throw Malformed(path: "\(path).day") }
            return BackupPlanEntry(
                id: id,
                day: day,
                mealTypeId: try r.string(o, "mealTypeId").flatMap { mealTypeIds.contains($0) ? $0 : nil },
                recipeId: try r.string(o, "recipeId").flatMap { recipeIds.contains($0) ? $0 : nil },
                servings: try r.int(o, "servings"),
                note: try r.string(o, "note").flatMap { $0.isBlank ? nil : $0 },
                sortOrder: try r.int(o, "sortOrder") ?? 0,
                updatedAt: try r.int64(o, "updatedAt") ?? 0
            )
        }
        try requireUniqueIds(mealPlan.map(\.id), "mealPlan")

        let menus = try top.objects(root, "menus").map { path, o -> BackupMenu in
            let r = Reader(path: path)
            let id = try r.requiredId(o, "id")
            guard let name = try r.string(o, "name"), !name.isBlank else { throw Malformed(path: "\(path).name") }
            return BackupMenu(id: id, name: name, updatedAt: try r.int64(o, "updatedAt") ?? 0)
        }
        try requireUniqueIds(menus.map(\.id), "menus")

        let menuIds = Set(menus.map(\.id))
        let menuEntries = try top.objects(root, "menuEntries").map { path, o -> BackupMenuEntry in
            let r = Reader(path: path)
            let id = try r.requiredId(o, "id")
            guard let menuId = try r.string(o, "menuId"), menuIds.contains(menuId) else {
                throw Malformed(path: "\(path).menuId")
            }
            guard let offset = try r.int(o, "dayOffset"), (0...6).contains(offset) else {
                throw Malformed(path: "\(path).dayOffset")
            }
            return BackupMenuEntry(
                id: id,
                menuId: menuId,
                dayOffset: offset,
                mealTypeId: try r.string(o, "mealTypeId").flatMap { mealTypeIds.contains($0) ? $0 : nil },
                recipeId: try r.string(o, "recipeId").flatMap { recipeIds.contains($0) ? $0 : nil },
                servings: try r.int(o, "servings"),
                note: try r.string(o, "note").flatMap { $0.isBlank ? nil : $0 },
                sortOrder: try r.int(o, "sortOrder") ?? 0,
                updatedAt: try r.int64(o, "updatedAt") ?? 0
            )
        }
        try requireUniqueIds(menuEntries.map(\.id), "menuEntries")

        // #116: a photo naming no recipe in the file is left out (it belongs to its recipe).
        let cookedPhotos = try top.objects(root, "cookedPhotos").compactMap { path, o -> BackupCookedPhoto? in
            let r = Reader(path: path)
            let id = try r.requiredId(o, "id")
            guard let file = try r.string(o, "file"), isPhotoFile(file) else { throw Malformed(path: "\(path).file") }
            guard let day = try r.int64(o, "day") else { throw Malformed(path: "\(path).day") }
            guard let recipeId = try r.string(o, "recipeId"), recipeIds.contains(recipeId) else { return nil }
            return BackupCookedPhoto(
                id: id, recipeId: recipeId, day: day,
                note: try r.string(o, "note").flatMap { $0.isBlank ? nil : $0 },
                createdAt: try r.int64(o, "createdAt") ?? 0, updatedAt: try r.int64(o, "updatedAt") ?? 0, file: file
            )
        }
        try requireUniqueIds(cookedPhotos.map(\.id), "cookedPhotos")

        return Backup(
            exportedAt: exportedAt, recipes: recipes, lists: lists, memberships: memberships,
            pantry: pantry, groceries: groceries, mealTypes: mealTypes, mealPlan: mealPlan,
            menus: menus, menuEntries: menuEntries, cookedPhotos: cookedPhotos
        )
    }

    /// A picture's path in the zip (#116): under `photos/`, one plain name, never `..` or a folder.
    static func isPhotoFile(_ path: String) -> Bool {
        path.range(of: "^photos/[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$", options: .regularExpression) != nil
    }

    private static func requireUniqueIds(_ ids: [String], _ section: String) throws {
        var seen = Set<String>()
        for (i, id) in ids.enumerated() where !seen.insert(id).inserted {
            throw Malformed(path: "\(section)[\(i)].id")
        }
    }

    private struct Malformed: Error { let path: String }

    /// Typed reads with the path of the field in every failure ("recipes[2].title"). A JSON
    /// null and a missing key are both "absent"; a value of the wrong type is malformed.
    private struct Reader {
        let path: String

        private func at(_ key: String) -> String { path.isEmpty ? key : "\(path).\(key)" }

        private func raw(_ o: [String: Any], _ key: String) -> Any? {
            guard let v = o[key], !(v is NSNull) else { return nil }
            return v
        }

        func string(_ o: [String: Any], _ key: String) throws -> String? {
            guard let v = raw(o, key) else { return nil }
            guard let s = v as? String else { throw Malformed(path: at(key)) }
            return s
        }

        func requiredId(_ o: [String: Any], _ key: String) throws -> String {
            guard let s = try string(o, key), !s.isBlank else { throw Malformed(path: at(key)) }
            return s
        }

        func bool(_ o: [String: Any], _ key: String) throws -> Bool? {
            guard let v = raw(o, key) else { return nil }
            guard let n = v as? NSNumber, BackupJson.isBoolean(n) else { throw Malformed(path: at(key)) }
            return n.boolValue
        }

        func int64(_ o: [String: Any], _ key: String) throws -> Int64? {
            guard let v = raw(o, key) else { return nil }
            guard let n = BackupJson.wholeNumber(v) else { throw Malformed(path: at(key)) }
            return n
        }

        func int(_ o: [String: Any], _ key: String) throws -> Int? {
            guard let v = try int64(o, key) else { return nil }
            guard v >= Int64(Int32.min), v <= Int64(Int32.max) else { throw Malformed(path: at(key)) }
            return Int(v)
        }

        func strings(_ o: [String: Any], _ key: String) throws -> [String] {
            guard let array = try array(o, key) else { return [] }
            return try array.enumerated().map { i, v in
                guard let s = v as? String else { throw Malformed(path: "\(at(key))[\(i)]") }
                return s
            }
        }

        func ints(_ o: [String: Any], _ key: String) throws -> [Int] {
            guard let array = try array(o, key) else { return [] }
            return try array.enumerated().map { i, v in
                guard let n = BackupJson.wholeNumber(v), n >= Int64(Int32.min), n <= Int64(Int32.max) else {
                    throw Malformed(path: "\(at(key))[\(i)]")
                }
                return Int(n)
            }
        }

        /// Each object in the array at `key`, with its path.
        func objects(_ o: [String: Any], _ key: String) throws -> [(String, [String: Any])] {
            guard let array = try array(o, key) else { return [] }
            return try array.enumerated().map { i, v in
                guard let item = v as? [String: Any] else { throw Malformed(path: "\(at(key))[\(i)]") }
                return ("\(at(key))[\(i)]", item)
            }
        }

        private func array(_ o: [String: Any], _ key: String) throws -> [Any]? {
            guard let v = raw(o, key) else { return nil }
            guard let a = v as? [Any] else { throw Malformed(path: at(key)) }
            return a
        }
    }

    fileprivate static func isBoolean(_ n: NSNumber) -> Bool {
        CFGetTypeID(n) == CFBooleanGetTypeID()
    }

    /// A JSON number with no fractional part (`3` or `3.0`), never a boolean.
    fileprivate static func wholeNumber(_ v: Any) -> Int64? {
        guard let n = v as? NSNumber, !isBoolean(n) else { return nil }
        let type = String(cString: n.objCType)
        if type == "d" || type == "f" {
            let d = n.doubleValue
            guard d.rounded() == d, d >= -9.2e18, d <= 9.2e18 else { return nil }
            return Int64(d)
        }
        return n.int64Value
    }

    private static func json(_ r: BackupRecipe) -> [String: Any] {
        [
            "id": r.id,
            "sourceUrl": r.sourceUrl,
            "sourceType": r.sourceType,
            "title": r.title,
            "imageUrl": r.imageUrl ?? NSNull(),
            "ingredients": r.ingredients,
            "instructions": r.instructions,
            "prepTime": r.prepTime ?? NSNull(),
            "cookTime": r.cookTime ?? NSNull(),
            "totalTime": r.totalTime ?? NSNull(),
            "servings": r.servings ?? NSNull(),
            "lastViewedAt": NSNumber(value: r.lastViewedAt),
            "checkedIngredients": r.checkedIngredients.sorted(),
            "notes": r.notes ?? NSNull(),
            "language": r.language ?? NSNull(),
            "contentOrigin": r.contentOrigin,
            "editedAt": r.editedAt.map { NSNumber(value: $0) } ?? NSNull(),
        ]
    }

    private static func json(_ p: BackupPantryItem) -> [String: Any] {
        [
            "id": p.id,
            "name": p.name,
            "quantity": p.quantity ?? NSNull(),
            "language": p.language ?? NSNull(),
            "aisle": p.aisle,
            "inStock": p.inStock,
            "alwaysHave": p.alwaysHave,
            "purchasedDay": p.purchasedDay.map { NSNumber(value: $0) } ?? NSNull(),
            "expiresDay": p.expiresDay.map { NSNumber(value: $0) } ?? NSNull(),
            "updatedAt": NSNumber(value: p.updatedAt),
        ]
    }

    private static func json(_ g: BackupGroceryItem) -> [String: Any] {
        [
            "id": g.id,
            "text": g.text,
            "language": g.language ?? NSNull(),
            "aisle": g.aisle,
            "checked": g.checked,
            "recipeId": g.recipeId ?? NSNull(),
            "plannedDay": g.plannedDay.map { NSNumber(value: $0) } ?? NSNull(),
            "updatedAt": NSNumber(value: g.updatedAt),
        ]
    }

    private static func json(_ t: BackupMealType) -> [String: Any] {
        [
            "id": t.id,
            "name": t.name,
            "builtInKey": t.builtInKey ?? NSNull(),
            "sortOrder": t.sortOrder,
            "updatedAt": NSNumber(value: t.updatedAt),
        ]
    }

    private static func json(_ e: BackupPlanEntry) -> [String: Any] {
        [
            "id": e.id,
            "day": NSNumber(value: e.day),
            "mealTypeId": e.mealTypeId ?? NSNull(),
            "recipeId": e.recipeId ?? NSNull(),
            "servings": e.servings.map { NSNumber(value: $0) } ?? NSNull(),
            "note": e.note ?? NSNull(),
            "sortOrder": e.sortOrder,
            "updatedAt": NSNumber(value: e.updatedAt),
        ]
    }

    private static func json(_ m: BackupMenu) -> [String: Any] {
        ["id": m.id, "name": m.name, "updatedAt": NSNumber(value: m.updatedAt)]
    }

    private static func json(_ e: BackupMenuEntry) -> [String: Any] {
        [
            "id": e.id,
            "menuId": e.menuId,
            "dayOffset": e.dayOffset,
            "mealTypeId": e.mealTypeId ?? NSNull(),
            "recipeId": e.recipeId ?? NSNull(),
            "servings": e.servings.map { NSNumber(value: $0) } ?? NSNull(),
            "note": e.note ?? NSNull(),
            "sortOrder": e.sortOrder,
            "updatedAt": NSNumber(value: e.updatedAt),
        ]
    }

    private static func json(_ p: BackupCookedPhoto) -> [String: Any] {
        [
            "id": p.id,
            "recipeId": p.recipeId,
            "day": NSNumber(value: p.day),
            "note": p.note ?? NSNull(),
            "createdAt": NSNumber(value: p.createdAt),
            "updatedAt": NSNumber(value: p.updatedAt),
            "file": p.file,
        ]
    }

    private static func json(_ l: BackupList) -> [String: Any] {
        [
            "id": l.id,
            "name": l.name,
            "isFavorites": l.isFavorites,
            "isBuiltIn": l.isBuiltIn,
            "sortOrder": l.sortOrder,
            "createdAt": NSNumber(value: l.createdAt),
        ]
    }

    private static func json(_ m: BackupMembership) -> [String: Any] {
        ["recipeId": m.recipeId, "listId": m.listId, "addedAt": NSNumber(value: m.addedAt)]
    }
}

private extension String {
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
