import Foundation

/// A page's readable text, for a page with no recipe data (#103): what the on-device model may
/// pick a recipe from. Built from the HTML by `PageTextReader`, one line per block (paragraph,
/// list item, heading, table row), each as Jsoup's `text()` gives it, with scripts, styles,
/// navigation and footers left out. Android's `PageText`.
struct PageText: Equatable {
    /// The page's first `<h1>`, else its `og:title`, else its `<title>`; nil if none.
    var title: String?
    var lines: [String]
    /// `<html lang>`, as declared ("en-US"), or nil.
    var language: String? = nil
    /// `og:image` as an absolute link, or nil.
    var image: String? = nil
}

/// What the model picked out of a page's text, field by field, before `PageRecipeCheck` keeps
/// only what is really on the page. Every string is meant to be copied from the page as written.
struct PageSelection: Equatable {
    var name: String?
    var ingredients: [String]
    var steps: [String]
    var yield: String? = nil
    var prepTime: String? = nil
    var cookTime: String? = nil
    var totalTime: String? = nil
}
