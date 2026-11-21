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

    @Test fun `with proUnlock on the free tier never limits anything, whatever freeTier and the unlock say`() {
        for (freeTier in listOf(false, true)) for (unlocked in listOf(false, true)) {
            assertEquals(
                LibraryLimit.Unlimited,
                LibraryLimit.of(freeTier = freeTier, unlocked = unlocked, oneCookbook = true, proUnlock = true)
            )
            // With oneCookbook off, its own old clean-up returns, as with the free tier off: never the free 20.
            assertEquals(
                LibraryLimit.History(50),
                LibraryLimit.of(freeTier = freeTier, unlocked = unlocked, oneCookbook = false, proUnlock = true)
            )
        }
    }

    @Test fun `with proUnlock off the free tier's rules come back exactly`() {
        assertEquals(LibraryLimit.Capped(50), LibraryLimit.of(freeTier = true, unlocked = false, oneCookbook = true, proUnlock = false))
        assertEquals(LibraryLimit.Free(20), LibraryLimit.of(freeTier = true, unlocked = false, oneCookbook = false, proUnlock = false))
    }

    @Test fun `the screens say the limit the flag gives`() {
        assertEquals(50, LibraryLimit.freeRecipes(oneCookbook = true))
        assertEquals(20, LibraryLimit.freeRecipes(oneCookbook = false))
    }
}
