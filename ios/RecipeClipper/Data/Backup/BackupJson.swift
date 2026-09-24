import Foundation

/// The export file's JSON, both ways (Android's BackupJson). Pure: text in, data out.
///
/// ```
/// { "format": "recipe-clipper-backup", "formatVersion": 1, "exportedAt": <epoch ms>,
///   "recipes":     [{ "id", "sourceUrl", "sourceType", "title", "imageUrl", "ingredients",
///                     "instructions", "prepTime", "cookTime", "totalTime", "servings",
///                     "lastViewedAt", "checkedIngredients", "notes", "language" }],
///   "lists":       [{ "id", "name", "isFavorites", "isBuiltIn", "sortOrder", "createdAt" }],
///   "memberships": [{ "recipeId", "listId", "addedAt" }] }
/// ```
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
        ]
        guard let data = try? JSONSerialization.data(
            withJSONObject: root, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
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
                language: try r.string(o, "language")
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

        return Backup(exportedAt: exportedAt, recipes: recipes, lists: lists, memberships: memberships)
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
