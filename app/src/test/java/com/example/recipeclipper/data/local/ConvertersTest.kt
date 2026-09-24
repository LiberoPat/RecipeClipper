package com.example.recipeclipper.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {

    private val converters = Converters()

    private fun roundTrip(list: List<String>) =
        converters.jsonToStringList(converters.stringListToJson(list))

    @Test fun `a list survives the round trip`() {
        val list = listOf("1 cup flour", "2 eggs", "salt")
        assertEquals(list, roundTrip(list))
    }

    @Test fun `commas quotes newlines and unicode survive`() {
        val list = listOf(
            "1 cup flour, sifted",
            "\"good\" olive oil",
            "line one\nline two",
            "½ tsp salt — fine",
            "back\\slash",
            "日本語"
        )
        assertEquals(list, roundTrip(list))
    }

    @Test fun `an empty list and blank entries are kept`() {
        assertEquals(emptyList<String>(), roundTrip(emptyList()))
        assertEquals(listOf("", "a", ""), roundTrip(listOf("", "a", "")))
    }

    @Test fun `order is preserved`() {
        val list = listOf("c", "a", "b")
        assertEquals(list, roundTrip(list))
    }

    @Test fun `a set of indexes survives the round trip`() {
        val set = setOf(0, 3, 7, 12)
        assertEquals(set, converters.jsonToIntSet(converters.intSetToJson(set)))
    }

    @Test fun `an empty set is kept`() {
        assertEquals(emptySet<Int>(), converters.jsonToIntSet(converters.intSetToJson(emptySet())))
    }
}
