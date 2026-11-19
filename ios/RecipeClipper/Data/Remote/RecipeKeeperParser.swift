import Foundation

/// One recipe of a Recipe Keeper export (#390; Android's `RecipeKeeperRecipe`), as far as the app
/// reads it. Every text is as Recipe Keeper holds it, trimmed. `id` is its `recipeId`; `sourceUrl`
/// a real web link (cleaned) or nil (Recipe Keeper writes "Recipe Keeper" there when the cook typed
/// the recipe in); `servings` is `recipeYield` as written ("Serves 6-8"); the times are the text of
/// `prepTime` and `cookTime` (ISO); `categories` are the recipe's courses, categories and
/// collections, which the cook may have become lists (#387); `photos` are the `src`s of `photo0`,
/// `photo1`, … in that order. An ingredient heading is a line ending in a colon (`IngredientHeading`).
struct RecipeKeeperRecipe: Equatable {
    var id: String?
    var name: String
    var ingredients: [String]
    var directions: [String]
    var notes: String?
    var servings: String?
    var prepTime: String?
    var cookTime: String?
    var sourceUrl: String?
    var categories: [String]
    var favorite: Bool
    var photos: [String]
}

/// What `recipes.html` held: its recipes, how many blocks weren't one, and how many blocks there were.
struct RecipeKeeperPage: Equatable {
    var recipes: [RecipeKeeperRecipe]
    var notRecipes: Int
    var blocks: Int
}

/// Reads Recipe Keeper's `recipes.html` (#390; Android's `RecipeKeeperParser`, which leans on
/// Jsoup; `HtmlTree` stands in for Jsoup's DOM here, and both are tested against the same pages).
/// The page is a run of `div.recipe-details`, each read **by `itemprop` only**: the visible labels
/// are translated ("Portate", "Porzioni"), the itemprops are not. The markup is not strict HTML
/// (unclosed `<meta>`, an unquoted `itemprop=photo0/`), which `HtmlTree` is forgiving about.
///
/// - A value is a `<meta>`'s `content`, else the element's text (the first course is a `<span>`,
///   any further ones `<meta>`s).
/// - Ingredients and directions are the `<p>`s of their `div`, blanks dropped. A bold ingredient
///   line (the whole line in `<b>`) is a heading, written with a colon if it has none. A direction
///   loses a leading "1. ".
/// - `recipeSource` is kept only when it is an http(s) link. A `PT0S` time is none by the time
///   rules, where the import maps it.
/// - A recipe needs a name plus ingredients or steps (the parsers' rule); a block without is
///   counted as not a recipe. Nutrition, rating and share id are not kept.
///
/// Pure: text in, data out. A BOM is stripped here.
enum RecipeKeeperParser {

    static func parse(_ text: String) -> RecipeKeeperPage {
        var body = text
        if body.hasPrefix("\u{FEFF}") { body.removeFirst() }
        let tree = HtmlTree(body)
        let blocks = tree.elements.indices.filter {
            tree.elements[$0].name == "div"
                && (tree.elements[$0].attributes["class"] ?? "").lowercased().split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" })
                    .contains("recipe-details")
        }
        var recipes: [RecipeKeeperRecipe] = []
        var notRecipes = 0
        for block in blocks {
            if let recipe = recipe(tree, block) { recipes.append(recipe) } else { notRecipes += 1 }
        }
        return RecipeKeeperPage(recipes: recipes, notRecipes: notRecipes, blocks: blocks.count)
    }

    private static func recipe(_ tree: HtmlTree, _ block: Int) -> RecipeKeeperRecipe? {
        var props: [String: [Int]] = [:]
        for index in tree.descendants(of: block) {
            guard let raw = tree.elements[index].attributes["itemprop"] else { continue }
            // `itemprop=photo0/` unquoted reads as "photo0/": the slash is not part of the name.
            var key = raw.kTrimmed
            while key.hasSuffix("/") { key.removeLast() }
            props[key, default: []].append(index)
        }
        func value(_ index: Int) -> String {
            let el = tree.elements[index]
            return (el.name == "meta" ? (el.attributes["content"] ?? "") : tree.text(of: index)).kTrimmed
        }
        func values(_ key: String) -> [String] { (props[key] ?? []).map(value).filter { !$0.isEmpty } }
        func first(_ key: String) -> String? { values(key).first }

        guard let name = first("name") else { return nil }
        let ingredients = props["recipeIngredients"]?.first.map { ingredientLines(tree, $0) } ?? []
        let directions = props["recipeDirections"]?.first.map { directionLines(tree, $0) } ?? []
        if ingredients.isEmpty && directions.isEmpty { return nil }

        let photos = props.compactMap { key, els in photoIndex(key).map { ($0, els) } }
            .sorted { $0.0 < $1.0 }
            .flatMap { _, els in els.compactMap { tree.elements[$0].attributes["src"]?.kTrimmed }.filter { !$0.isEmpty } }

        let notes = props["recipeNotes"]?.first.map { paragraphs(tree, $0).joined(separator: "\n") }
        return RecipeKeeperRecipe(
            id: first("recipeId"),
            name: name,
            ingredients: ingredients,
            directions: directions,
            notes: notes.flatMap { $0.isEmpty ? nil : $0 },
            servings: first("recipeYield"),
            prepTime: first("prepTime"),
            cookTime: first("cookTime"),
            sourceUrl: first("recipeSource").flatMap { WebImageUrl.of($0) }.map { UrlCleaner.clean($0) },
            categories: values("recipeCourse") + values("recipeCategory") + values("recipeCollection"),
            favorite: first("recipeIsFavourite")?.lowercased() == "true",
            photos: photos
        )
    }

    /// `photo0`, `photo12` (digits only after "photo"): its number, else nil.
    private static func photoIndex(_ key: String) -> Int? {
        guard key.hasPrefix("photo") else { return nil }
        let digits = key.dropFirst(5)
        guard !digits.isEmpty, digits.count <= 6, digits.allSatisfy({ $0 >= "0" && $0 <= "9" }) else { return nil }
        return Int(digits)
    }

    private static func paragraphElements(_ tree: HtmlTree, _ el: Int) -> [Int] {
        tree.descendants(of: el).filter { tree.elements[$0].name == "p" }
    }

    /// The text of each `<p>` in `el`, blanks dropped; with no `<p>`, the element's own text as one line.
    private static func paragraphs(_ tree: HtmlTree, _ el: Int) -> [String] {
        let ps = paragraphElements(tree, el)
        let lines = ps.isEmpty ? [tree.text(of: el)] : ps.map { tree.text(of: $0) }
        return lines.map(\.kTrimmed).filter { !$0.isEmpty }
    }

    private static func ingredientLines(_ tree: HtmlTree, _ el: Int) -> [String] {
        let ps = paragraphElements(tree, el)
        if ps.isEmpty { return paragraphs(tree, el) }
        return ps.compactMap { p in
            let text = tree.text(of: p).kTrimmed
            if text.isEmpty { return nil }
            let bold = tree.elements[p].children.compactMap { child -> String? in
                guard case .element(let c) = child, ["b", "strong"].contains(tree.elements[c].name) else { return nil }
                return tree.text(of: c)
            }.joined()
            let isHeading = !bold.kIsBlank && stripSpaces(bold) == stripSpaces(text)
            return isHeading && !text.hasSuffix(":") ? text + ":" : text
        }
    }

    private static func stripSpaces(_ s: String) -> String { s.filter { !$0.isWhitespace } }

    private static let number = JRegex(#"^\d{1,3}\.\s+"#)

    private static func directionLines(_ tree: HtmlTree, _ el: Int) -> [String] {
        paragraphs(tree, el).map { line -> String in
            guard let m = number.find(line, from: 0), m.start == 0 else { return line }
            return String((line as NSString).substring(from: m.end)).kTrimmed
        }.filter { !$0.isEmpty }
    }
}
