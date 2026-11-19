package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which limit applies (#107, #345): `oneCookbook` decides between today's rules and the cookbook's. */
class LibraryLimitTest {

    @Test fun `with oneCookbook off the old rules stand`() {
        assertEquals(LibraryLimit.History(50), LibraryLimit.of(freeTier = false, unlocked = false))
        assertEquals(LibraryLimit.Free(20), LibraryLimit.of(freeTier = true, unlocked = false))
        assertEquals(LibraryLimit.Unlimited, LibraryLimit.of(freeTier = true, unlocked = true))
    }

    @Test fun `with oneCookbook on, nothing is ever removed and the free tier stops at 50`() {
        assertEquals(LibraryLimit.Unlimited, LibraryLimit.of(freeTier = false, unlocked = false, oneCookbook = true))
        assertEquals(LibraryLimit.Unlimited, LibraryLimit.of(freeTier = true, unlocked = true, oneCookbook = true))
        assertEquals(LibraryLimit.Capped(50), LibraryLimit.of(freeTier = true, unlocked = false, oneCookbook = true))
    }

    @Test fun `the screens say the limit the flag gives`() {
        assertEquals(50, LibraryLimit.freeRecipes(oneCookbook = true))
        assertEquals(20, LibraryLimit.freeRecipes(oneCookbook = false))
    }
}
