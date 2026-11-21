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
/// - `capped`: `oneCookbook` on (#345), the free tier on and locked. Every recipe counts, and a new
///   one added at `max` is shown but not kept: nothing is ever removed to make room. Re-opening or
///   refreshing a recipe already here is allowed (it is already counted).
/// - `unlimited`: unlocked, or `oneCookbook` on with the free tier off, or with `proUnlock` (#413).
///   Nothing is removed automatically, ever.
enum LibraryLimit: Equatable, Sendable {
    case history(keep: Int)
    case free(max: Int)
    case capped(max: Int)
    case unlimited

    /// The free tier's size with `oneCookbook` off (#107): swaps the oldest one out at this many.
    static let freeRecipes = 20

    /// The free tier's size with `oneCookbook` on (#345): the next recipe isn't saved at this many.
    static let cappedRecipes = 50

    /// The free app's size as the screens say it, for the flag as it stands.
    static func freeRecipes(oneCookbook: Bool) -> Int { oneCookbook ? cappedRecipes : freeRecipes }

    /// The old cap on unprotected recipes, while the free tier is off.
    static let historyRecipes = 50

    /// `proUnlock` (#413) takes the free tier away whatever `freeTier` says: every recipe is free, so the
    /// limit is the one `oneCookbook` alone gives (`unlimited` on, the old `history` cull off).
    static func of(freeTier: Bool, unlocked: Bool, oneCookbook: Bool = false, proUnlock: Bool = false) -> LibraryLimit {
        if proUnlock { return oneCookbook ? .unlimited : .history(keep: historyRecipes) }
        if oneCookbook { return freeTier && !unlocked ? .capped(max: cappedRecipes) : .unlimited }
        if !freeTier { return .history(keep: historyRecipes) }
        return unlocked ? .unlimited : .free(max: freeRecipes)
    }
}
