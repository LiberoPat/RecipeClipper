import Foundation

/// How many recipes the library keeps (#107), as the repository applies it when one is added.
/// Android's `LibraryLimit`.
///
/// - `history`: the `freeTier` flag is off, so the app is unchanged: at most `keep` recipes that
///   are in no list, not planned for today or later, in no menu and not typed in; older ones are
///   culled after every save.
/// - `free`: every recipe counts. Adding one when there are already `max` or more first removes
///   the oldest-viewed recipe that is in no list, not planned (today or later), in no menu and not
///   typed in: one out for one in, never more. So the library never shrinks because of the
///   limit, and a library that was over it when the limit arrived keeps every recipe. If every
///   recipe is protected, the new one is shown but not kept.
/// - `unlimited`: unlocked. Nothing is removed automatically, ever.
enum LibraryLimit: Equatable, Sendable {
    case history(keep: Int)
    case free(max: Int)
    case unlimited

    /// The free tier's size.
    static let freeRecipes = 20

    /// The old cap on unprotected recipes, while the free tier is off.
    static let historyRecipes = 50

    static func of(freeTier: Bool, unlocked: Bool) -> LibraryLimit {
        if !freeTier { return .history(keep: historyRecipes) }
        return unlocked ? .unlimited : .free(max: freeRecipes)
    }
}
