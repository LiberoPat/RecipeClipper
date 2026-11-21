package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Today card's meal windows (#402): by built-in key, local time, a window's end exclusive. */
class MealWindowsTest {

    private fun at(hours: Int, minutes: Int = 0) = (hours * 60L + minutes) * 60_000

    @Test fun `breakfast leads until 11 00`() {
        assertTrue(MealWindows.isOpen("breakfast", at(10, 59)))
        assertFalse(MealWindows.isOpen("breakfast", at(11, 0)))
    }

    @Test fun `lunch leads until 15 00`() {
        assertTrue(MealWindows.isOpen("lunch", at(14, 59)))
        assertFalse(MealWindows.isOpen("lunch", at(15, 0)))
        assertFalse(MealWindows.isOpen("lunch", at(19, 0)))
    }

    @Test fun `dinner and snack last to the end of the day, and the cook's own types have no window`() {
        for (key in listOf("dinner", "snack", null, "brunch")) {
            assertTrue(MealWindows.isOpen(key, 0))
            assertTrue(MealWindows.isOpen(key, at(23, 59)))
        }
    }

    @Test fun `the next window to end, and how many have`() {
        assertEquals(at(11), MealWindows.untilNextEnd(0))
        assertEquals(60_000L, MealWindows.untilNextEnd(at(10, 59)))
        assertEquals(at(4), MealWindows.untilNextEnd(at(11)))
        assertNull(MealWindows.untilNextEnd(at(15)))
        assertEquals(listOf(0, 0, 1, 1, 2), listOf(0L, at(10, 59), at(11), at(14, 59), at(15)).map(MealWindows::ended))
    }
}
