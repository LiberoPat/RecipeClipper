import Foundation

/// The `recipeclipper://` links the widget extension opens the app with (#309): a running
/// timer's Live Activity opens its recipe in cook mode, a widget its tab. Compiled into the app
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

    /// The recipe a cook link names, or nil for any other URL.
    static func cookRecipeId(from url: URL) -> Int64? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == cookHost,
              let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == recipeIdItem })?.value
        else { return nil }
        return Int64(value)
    }
}
