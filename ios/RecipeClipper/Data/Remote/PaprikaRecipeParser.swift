import Foundation

/// One recipe of a Paprika export (#382; Android's `PaprikaRecipe`), as far as the app reads it.
/// Every text is as Paprika holds it, trimmed: nothing here is converted or guessed. `uid` is
/// Paprika's own id (the identity of the recipe in an export, which a re-import uses), `sourceUrl`
/// a real web link (cleaned) or nil, `imageUrl` a web picture link or nil. `hasPhoto` is whether
/// the file carries the recipe's picture itself (`photo_data`, or the first of `photos`).
/// `categories` are Paprika's own filing; the cook may have them become lists (#387). `favorite` is
/// Paprika's own flag (`on_favorites`).
///
/// `photoData` is that picture as the base64 text the file holds it, only until the zip reader has
/// handed it to the photo store (#386): it is then nil, and `photoFile` names the stored file (nil
/// if the picture was left out: too big, or not an image).
struct PaprikaRecipe: Equatable {
    var uid: String?
    var name: String
    var ingredients: [String]
    var directions: [String]
    var notes: String?
    var servings: String?
    var prepTime: String?
    var cookTime: String?
    var totalTime: String?
    var sourceUrl: String?
    var imageUrl: String?
    var categories: [String]
    var hasPhoto: Bool
    var photoData: String? = nil
    var photoFile: String? = nil
    var favorite = false
}

/// What one entry of the export said.
enum PaprikaEntry: Equatable {
    case recipe(PaprikaRecipe)
    /// A JSON object with no name, or with neither ingredients nor steps: not a recipe, skipped and counted.
    case notARecipe
    /// Not JSON, not an object, or too deeply nested to read: counted as unreadable.
    case unreadable
}

/// Reads the JSON of one `.paprikarecipe` entry (the gunzipped text; Android's
/// `PaprikaRecipeParser`). The fields are the ones the libpaprika crate documents (`name`,
/// `ingredients` and `directions` as newline-separated text, `notes`, `servings`, `prep_time`,
/// `cook_time`, `total_time`, `source_url`, `image_url`, `categories`, `photo_data`, `uid`); a
/// missing, null or unknown field is simply absent. A recipe needs a name plus ingredients or
/// steps, the parsers' rule. Pure: text in, data out.
enum PaprikaRecipeParser {

    static func parse(_ text: String) -> PaprikaEntry {
        var body = text
        if body.hasPrefix("\u{FEFF}") { body.removeFirst() }
        // Foundation refuses nesting past 512 levels instead of overflowing the stack.
        guard let root = try? JSONSerialization.jsonObject(with: Data(body.utf8), options: []),
              let json = root as? [String: Any] else { return .unreadable }

        let name = string(json["name"]) ?? ""
        let ingredients = RecipeDraft.lines(string(json["ingredients"]) ?? "")
        let directions = RecipeDraft.lines(string(json["directions"]) ?? "")
        if name.isEmpty || (ingredients.isEmpty && directions.isEmpty) { return .notARecipe }

        let photos = json["photos"] as? [Any]
        // The picture: photo_data, else the first of the photos' data (libpaprika's RecipePhoto).
        let photoData = string(json["photo_data"]) ?? (photos ?? []).lazy.compactMap { ($0 as? [String: Any]).flatMap { string($0["data"]) } }.first
        return .recipe(PaprikaRecipe(
            uid: string(json["uid"]),
            name: name,
            ingredients: ingredients,
            directions: directions,
            notes: string(json["notes"]),
            servings: string(json["servings"]),
            prepTime: string(json["prep_time"]),
            cookTime: string(json["cook_time"]),
            totalTime: string(json["total_time"]),
            sourceUrl: string(json["source_url"]).flatMap(webLink),
            imageUrl: WebImageUrl.of(string(json["image_url"])),
            categories: (json["categories"] as? [Any])?.compactMap { item in
                (item as? String).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
            } ?? [],
            hasPhoto: photoData != nil || !(photos?.isEmpty ?? true),
            photoData: photoData,
            favorite: flag(json["on_favorites"])
        ))
    }

    /// A real web link, cleaned as every saved link is; anything else ("", "n/a", a name) is none.
    private static func webLink(_ text: String) -> String? {
        WebImageUrl.of(text).map { UrlCleaner.clean($0) }
    }

    /// A switch as Paprika writes it: a boolean, or a number that isn't 0 (`on_favorites` is 0 or 1).
    private static func flag(_ value: Any?) -> Bool {
        guard let number = value as? NSNumber else { return false }
        if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue }
        return number.doubleValue != 0
    }

    /// A text field, trimmed; nil if absent, JSON null, blank or not text (a whole number is its
    /// digits; a boolean is not text). The JSON value is read as it is, never `"null"` for null.
    private static func string(_ value: Any?) -> String? {
        let text: String?
        switch value {
        case let string as String:
            text = string
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                text = nil
            } else if number.doubleValue == Double(number.int64Value) {
                text = String(number.int64Value)
            } else {
                text = number.stringValue
            }
        default:
            text = nil
        }
        return text.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
    }
}
