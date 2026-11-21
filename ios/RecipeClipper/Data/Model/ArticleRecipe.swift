import Foundation

/// A recipe written inside an ordinary article (#435), found on a page whose JSON-LD and microdata
/// hold none: for the cook to check in the editor, never saved unchecked. `name` is the nearest
/// heading above its list, else the page's `headline` (neither may be the dish's name), `image` the
/// page's `og:image`, `ingredients` the list's items as written, `steps` what follows it as written,
/// `language` the language whose words found the list, and `sourceUrl` the link it is saved under
/// (`link`). Codable, so the share extension can leave it for the app (`PendingArticle`).
/// Android's `ArticleRecipe`.
struct ArticleRecipe: Hashable, Codable {
    var name: String
    var image: String?
    var ingredients: [String]
    var steps: [String]
    var language: String
    var sourceUrl: String
    var headline: String

    init(
        name: String, image: String?, ingredients: [String], steps: [String], language: String, sourceUrl: String,
        headline: String? = nil
    ) {
        self.name = name; self.image = image; self.ingredients = ingredients; self.steps = steps
        self.language = language; self.sourceUrl = sourceUrl; self.headline = headline ?? name
    }

    /// The query parameter that tells a page's second and later recipes apart (#435).
    static let parameter = "pith_recipe"

    /// The link the page's recipe at `index` (from 0, in page order) is saved under: the page's
    /// cleaned link for the first; for each later one the same link with `pith_recipe=<n>` (from 2)
    /// added, which `UrlCleaner` keeps, so every recipe of the page has its own `sourceUrl`.
    static func link(_ pageUrl: String, index: Int) -> String {
        if index <= 0 { return pageUrl }
        return pageUrl + (pageUrl.contains("?") ? "&" : "?") + "\(parameter)=\(index + 1)"
    }
}
