import Foundation

/// The `recipeclipper://` links the widget extension opens the app with (#309): a running
/// timer's Live Activity opens its recipe in cook mode, a home-screen widget its tab. Compiled into the app
/// (where `DeepLink` reads them) and the widget extension (which writes them), so the two
/// can't drift.
enum AppLinks {
    static let scheme = "recipeclipper"
    static let cookHost = "cook"
    static let recipeIdItem = "id"

    /// A recipe in cook mode: `recipeclipper://cook?id=7`.
    static func cook(recipeId: Int64) -> URL {
        URL(string: "\(scheme)://\(cookHost)?\(recipeIdItem)=\(recipeId)")!
    }

    static let tabHost = "tab"
    static let tabNameItem = "name"

    /// A tab, by `AppTab`'s raw value: `recipeclipper://tab?name=week`. Tonight opens the Week,
    /// the grocery widget Groceries.
    static func tab(_ name: String) -> URL {
        URL(string: "\(scheme)://\(tabHost)?\(tabNameItem)=\(name)")!
    }

    /// The tab name a tab link carries, or nil for any other URL.
    static func tabName(from url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == tabHost else { return nil }
        return URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == tabNameItem })?.value
    }

    /// The recipe a cook link names, or nil for any other URL.
    static func cookRecipeId(from url: URL) -> Int64? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == cookHost,
              let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == recipeIdItem })?.value
        else { return nil }
        return Int64(value)
    }
}
