package com.example.recipeclipper.data.model

/**
 * How many recipes the library keeps (#107), as the repository applies it when one is added.
 *
 * - [History]: the `freeTier` flag is off, so the app is unchanged: at most [History.keep]
 *   recipes that are in no list, not planned for today or later, in no menu and not typed in;
 *   older ones are culled after every save.
 * - [Free]: every recipe counts. Adding one when there are already [Free.max] or more first
 *   removes the oldest-viewed recipe that is in no list, not planned (today or later), in no
 *   menu and not typed in: one out for one in, never more. So the library never shrinks
 *   because of the limit, and a library that was over it when the limit arrived keeps every
 *   recipe. If every recipe is protected, the new one is shown but not kept.
 * - [Capped]: `oneCookbook` on (#345), the free tier on and locked. Every recipe counts, and a
 *   new one added at [Capped.max] is shown but not kept: nothing is ever removed to make room.
 *   Re-opening or refreshing a recipe already here is allowed (it is already counted).
 * - [Unlimited]: unlocked, or `oneCookbook` on with the free tier off. Nothing is removed
 *   automatically, ever.
 */
sealed interface LibraryLimit {
    data class History(val keep: Int) : LibraryLimit
    data class Free(val max: Int) : LibraryLimit
    data class Capped(val max: Int) : LibraryLimit
    data object Unlimited : LibraryLimit

    companion object {
        /** The free tier's size with `oneCookbook` off (#107): swaps the oldest one out at this many. */
        const val FREE_RECIPES = 20

        /** The free tier's size with `oneCookbook` on (#345): the next recipe isn't saved at this many. */
        const val CAPPED_RECIPES = 50

        /** The free app's size as the screens say it, for the flag as it stands. */
        fun freeRecipes(oneCookbook: Boolean): Int = if (oneCookbook) CAPPED_RECIPES else FREE_RECIPES

        /** The old cap on unprotected recipes, while the free tier is off. */
        const val HISTORY_RECIPES = 50

        fun of(freeTier: Boolean, unlocked: Boolean, oneCookbook: Boolean = false): LibraryLimit = when {
            oneCookbook -> if (freeTier && !unlocked) Capped(CAPPED_RECIPES) else Unlimited
            !freeTier -> History(HISTORY_RECIPES)
            unlocked -> Unlimited
            else -> Free(FREE_RECIPES)
        }
    }
}
