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
 * - [Unlimited]: unlocked. Nothing is removed automatically, ever.
 */
sealed interface LibraryLimit {
    data class History(val keep: Int) : LibraryLimit
    data class Free(val max: Int) : LibraryLimit
    data object Unlimited : LibraryLimit

    companion object {
        /** The free tier's size. */
        const val FREE_RECIPES = 20

        /** The old cap on unprotected recipes, while the free tier is off. */
        const val HISTORY_RECIPES = 50

        fun of(freeTier: Boolean, unlocked: Boolean): LibraryLimit = when {
            !freeTier -> History(HISTORY_RECIPES)
            unlocked -> Unlimited
            else -> Free(FREE_RECIPES)
        }
    }
}
