import Foundation

/// One recipe of a Mealie export (#391; Android's `MealieRecipe`), as far as the app reads it. Every
/// text is Mealie's, trimmed; nothing is converted or guessed. `slug` is Mealie's own name for the
/// recipe (the identity of a recipe in an export, which a re-import uses) and `id` its database id;
/// `sourceUrl` is `orgURL` when it is a real web link (cleaned), else nil. `ingredients` are lines of
/// text, with a heading line ending in a colon before the first line of each titled group;
/// `instructions` are the steps, a step's title in front of its text. `categories` are the recipe's
/// categories and tags together, by name; the cook may have them become lists (#387). `photoFile`
/// names the recipe's stored picture, set by the zip reader once the photo store took it (#386).
struct MealieRecipe: Equatable {
    var slug: String
    var id: String?
    var name: String
    var ingredients: [String]
    var instructions: [String]
    var notes: String?
    var servings: String?
    var prepTime: String?
    var cookTime: String?
    var totalTime: String?
    var sourceUrl: String?
    var categories: [String]
    var photoFile: String? = nil
}

/// What one JSON file of a Mealie export said.
enum MealieEntry: Equatable {
    case recipe(MealieRecipe)
    /// Shaped like a Mealie recipe, with no name, or neither ingredients nor steps: skipped and counted.
    case notARecipe
    /// A JSON object that isn't a Mealie recipe: no slug, no `referenceId` on its ingredients, or a
    /// schema.org file (`@context` / `@type`, which a recipe page's link import already reads).
    case notMealie
    /// Not JSON, not an object, or too deeply nested to read.
    case unreadable
}

/// Reads the JSON of one Mealie recipe (#391; Android's `MealieRecipeParser`): the bulk export's
/// `recipes/<slug>/<slug>.json`, the per-recipe download's `.json`, or a bare file. Mealie writes its keys
/// in snake_case or camelCase depending on where the file came from, so both are read. A missing, null or
/// unknown field is simply absent. A recipe needs a name plus ingredients or steps, the parsers' rule.
/// Pure: text in, data out.
///
/// - **Detected, not assumed:** a non-blank `slug` and `recipeIngredient` entries that are objects with a
///   `referenceId`, and no `@context` / `@type`.
/// - **Ingredients are never rebuilt from `quantity` and `unit`.** Before Mealie 3.1 a line that was never
///   parsed was stored with `quantity` 1 and its text in `note`, so rebuilding would show a confident wrong
///   number. The line is `originalText`, else (for a line with a food or a unit) Mealie's own `display`,
///   else the food's name and the note, as written. A `title` puts a heading line before it.
/// - **Steps** are their `text`; a `title` goes in front of it, and a step that is only a title goes in
///   front of the next step's text (the app has no step headings).
/// - **Yield:** `recipeYield` as text, else `recipeServings` as a number (whole when it is whole).
/// - **Times:** text, for the time rules to read; a `…Seconds` number becomes an ISO duration.
/// - **Not kept:** `description` (the app has no place for it), ratings, nutrition, tools, comments, assets.
enum MealieRecipeParser {

    static func parse(_ text: String) -> MealieEntry {
        var body = text
        if body.hasPrefix("\u{FEFF}") { body.removeFirst() }
        // Foundation refuses nesting past 512 levels instead of overflowing the stack.
        guard let root = try? JSONSerialization.jsonObject(with: Data(body.utf8), options: []),
              let json = root as? [String: Any] else { return .unreadable }
        if json["@context"] != nil || json["@type"] != nil { return .notMealie }

        guard let slug = string(json, "slug") else { return .notMealie }
        guard let raw = pick(json, "recipeIngredient", "recipe_ingredient") as? [Any] else { return .notMealie }
        var items: [[String: Any]] = []
        for entry in raw {
            guard let object = entry as? [String: Any] else { return .notMealie }
            items.append(object)
        }
        let steps = pick(json, "recipeInstructions", "recipe_instructions") as? [Any]
        let hasReference = items.contains { $0["referenceId"] != nil || $0["reference_id"] != nil }
        // An empty ingredient list can't show a referenceId: a recipe of steps alone is told by having steps' key.
        if items.isEmpty ? steps == nil : !hasReference { return .notMealie }

        let name = string(json, "name") ?? ""
        let ingredients = ingredientLines(items)
        let instructions = stepLines(steps)
        if name.isEmpty || (!ingredients.contains { !$0.hasSuffix(":") } && instructions.isEmpty) { return .notARecipe }

        let servings = string(json, "recipeYield", "recipe_yield") ?? number(json, "recipeServings", "recipe_servings")
        return .recipe(MealieRecipe(
            slug: slug,
            id: string(json, "id"),
            name: name,
            ingredients: ingredients,
            instructions: instructions,
            notes: notes(pick(json, "notes")),
            servings: servings,
            prepTime: time(json, "prep"),
            cookTime: time(json, "perform", "cook"),
            totalTime: time(json, "total"),
            sourceUrl: string(json, "orgURL", "orgUrl", "org_url").flatMap(webLink),
            categories: names(pick(json, "recipeCategory", "recipe_category")) + names(pick(json, "tags"))
        ))
    }

    /// A real web link, cleaned as every saved link is; anything else ("", "n/a", a name) is none.
    private static func webLink(_ text: String) -> String? {
        WebImageUrl.of(text).map { UrlCleaner.clean($0) }
    }

    private static func ingredientLines(_ items: [[String: Any]]) -> [String] {
        var lines: [String] = []
        for item in items {
            if let title = string(item, "title") { lines.append(title.hasSuffix(":") ? title : title + ":") }
            if let line = string(item, "originalText", "original_text") ?? derivedLine(item) { lines.append(oneLine(line)) }
        }
        return lines
    }

    /// The line when `originalText` is missing. A line with no food and no unit is Mealie's unparsed line
    /// (before 3.1 stored with `quantity` 1): its `note` is the whole text, or it has none, and its `display`
    /// may be "1 " in front of it, so the note is the line. Otherwise Mealie's own `display`, else the food's
    /// name and the note. Never rebuilt from `quantity` and `unit`.
    private static func derivedLine(_ item: [String: Any]) -> String? {
        var food: String?
        if let object = item["food"] as? [String: Any] { food = string(object, "name") }
        else if let text = item["food"] as? String { food = clean(text) }
        var unit: String?
        if let object = item["unit"] as? [String: Any] { unit = string(object, "name") ?? string(object, "abbreviation") }
        else if let text = item["unit"] as? String { unit = clean(text) }
        let note = string(item, "note")
        if food == nil && unit == nil { return note }
        if let display = string(item, "display") { return display }
        let joined = [food, note].compactMap { $0 }.joined(separator: ", ")
        return joined.isEmpty ? nil : joined
    }

    private static func stepLines(_ steps: [Any]?) -> [String] {
        var lines: [String] = []
        var pending: String?
        for step in steps ?? [] {
            var title: String?
            var body: String?
            if let object = step as? [String: Any] {
                title = string(object, "title")
                body = string(object, "text")
            } else if let text = step as? String {
                body = clean(text)
            } else {
                continue
            }
            guard let text = body else {
                // Only a title: the heading of what follows.
                if let title { pending = [pending, title].compactMap { $0 }.joined(separator: " ") }
                continue
            }
            let head = [pending, title].compactMap { $0 }.joined(separator: " ")
            pending = nil
            if head.isEmpty { lines.append(text) } else { lines.append(head.hasSuffix(":") ? "\(head) \(text)" : "\(head): \(text)") }
        }
        return lines
    }

    private static func notes(_ value: Any?) -> String? {
        guard let array = value as? [Any] else { return nil }
        let paragraphs: [String] = array.compactMap { note in
            if let object = note as? [String: Any] {
                let text = [string(object, "title"), string(object, "text")].compactMap { $0 }.joined(separator: "\n")
                return text.isEmpty ? nil : text
            }
            if let text = note as? String { return clean(text) }
            return nil
        }
        let joined = paragraphs.joined(separator: "\n\n")
        return joined.isEmpty ? nil : joined
    }

    /// Names of categories or tags: `{name}` objects, or bare strings.
    private static func names(_ value: Any?) -> [String] {
        guard let array = value as? [Any] else { return [] }
        return array.compactMap { item in
            if let object = item as? [String: Any] { return string(object, "name") }
            if let text = item as? String { return clean(text) }
            return nil
        }
    }

    /// A time as text in any of Mealie's spellings, else a `…Seconds` number as an ISO duration.
    private static func time(_ json: [String: Any], _ stems: String...) -> String? {
        for stem in stems {
            if let text = string(json, "\(stem)Time", "\(stem)_time") { return text }
        }
        for stem in stems {
            if let value = pick(json, "\(stem)TimeSeconds", "\(stem)_time_seconds"), let seconds = numeric(value),
               seconds > 0, seconds < 1e9 {
                return "PT\(Int64(seconds))S"
            }
        }
        return nil
    }

    /// A line is one line: Mealie's own line breaks inside a field become spaces.
    private static func oneLine(_ text: String) -> String {
        text.split(whereSeparator: { $0 == "\n" || $0 == "\r" })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    /// The first of `keys` the object has with a value that isn't JSON null.
    private static func pick(_ json: [String: Any], _ keys: String...) -> Any? {
        pick(json, keys)
    }

    private static func pick(_ json: [String: Any], _ keys: [String]) -> Any? {
        for key in keys {
            if let value = json[key], !(value is NSNull) { return value }
        }
        return nil
    }

    private static func clean(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func isBool(_ number: NSNumber) -> Bool { CFGetTypeID(number) == CFBooleanGetTypeID() }

    private static func numeric(_ value: Any) -> Double? {
        guard let number = value as? NSNumber, !isBool(number) else { return nil }
        return number.doubleValue
    }

    /// A text field, trimmed; nil if absent, JSON null, blank or not text. A number keeps its digits (a
    /// whole number is its digits); a boolean is not text.
    private static func string(_ json: [String: Any], _ keys: String...) -> String? {
        for key in keys {
            let text: String?
            switch pick(json, [key]) {
            case let string as String: text = string
            case let number as NSNumber:
                if isBool(number) { text = nil }
                else if number.doubleValue == number.doubleValue.rounded(), abs(number.doubleValue) < 1e15 { text = String(number.int64Value) }
                else { text = number.stringValue }
            default: text = nil
            }
            if let text, let cleaned = clean(text) { return cleaned }
        }
        return nil
    }

    /// A positive number as text: whole when it is whole ("4"), else as written ("2.5"); nil for 0 or less.
    private static func number(_ json: [String: Any], _ keys: String...) -> String? {
        guard let value = pick(json, keys), let number = value as? NSNumber, !isBool(number) else { return nil }
        let d = number.doubleValue
        if !(d > 0) || d >= 1e9 { return nil }
        return d == d.rounded() ? String(Int64(d)) : number.stringValue
    }
}
