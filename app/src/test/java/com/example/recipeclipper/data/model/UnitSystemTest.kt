package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a stored enum name (SharedPreferences `unit_system`) reads back. */
class UnitSystemTest {

    @Test fun `the three options read back as themselves`() {
        UnitSystem.values().forEach { assertEquals(it, UnitSystem.fromStoredName(it.name)) }
    }

    @Test fun `a stored GRAMS, the option #17 removed, reads as metric`() {
        assertEquals(UnitSystem.METRIC, UnitSystem.fromStoredName("GRAMS"))
    }

    @Test fun `nothing stored, or an unknown name, reads as as written`() {
        assertEquals(UnitSystem.AS_WRITTEN, UnitSystem.fromStoredName(null))
        assertEquals(UnitSystem.AS_WRITTEN, UnitSystem.fromStoredName(""))
        assertEquals(UnitSystem.AS_WRITTEN, UnitSystem.fromStoredName("KELVIN"))
    }
}
