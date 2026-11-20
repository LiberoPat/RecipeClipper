import Foundation

/// Which app an import reads (#382 Paprika, #390 Recipe Keeper; Android's `ImportSource`). Both
/// come in as `PaprikaRecipe`s through the same repository, merger and summary; the source only
/// decides how a file is read (its zip reader) and the prefix of a recipe's own key when it has no
/// link (`keyPrefix`, so `manual:paprika-…` and `manual:recipekeeper-…` can never meet).
enum ImportSource: Equatable {
    case paprika
    case recipeKeeper

    var keyPrefix: String {
        switch self {
        case .paprika: return "paprika"
        case .recipeKeeper: return "recipekeeper"
        }
    }
}
