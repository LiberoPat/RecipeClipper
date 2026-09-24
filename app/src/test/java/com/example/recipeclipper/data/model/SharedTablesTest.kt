package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every shared table (#9) loads from the classpath, as the app loads it, and is well formed. */
class SharedTablesTest {

    private val languageTables = listOf("densities", "units", "timers", "temperature", "yield", "ranges", "sections", "names")

    @Test
    fun everyTableOnDiskIsCoveredHere() {
        val onDisk = File("../shared/tables/${SharedTables.LANGUAGE}").list()!!
            .map { it.removeSuffix(".json") }.toSet()
        assertEquals(languageTables.toSet(), onDisk)
    }

    @Test
    fun everyTableLoadsWithItsSchemaVersion() {
        for (name in languageTables) {
            val table = SharedTables.load(name)
            assertEquals(name, 1, table.getInt("schemaVersion"))
            assertEquals(name, SharedTables.LANGUAGE, table.getString("language"))
        }
        assertEquals(1, SharedTables.read("url").getInt("schemaVersion"))
    }

    @Test
    fun everyUnitNameIsAMeasureUnit() {
        val names = SharedTables.objects(SharedTables.load("units").getJSONArray("names"))
        for (name in names) MeasureUnit.valueOf(name.getString("unit"))
        assertEquals(MeasureUnit.values().toSet(), names.map { MeasureUnit.valueOf(it.getString("unit")) }.toSet())
    }

    @Test
    fun theCodeReadsTheTables() {
        assertNotNull(IngredientDensities.find("1 cup flour"))
        assertEquals(MeasureUnit.TBSP, MeasureUnit.fromText("Tbsp."))
        assertEquals(90 * 60, StepTimers.parse("Bake 1 hour and 30 minutes"))
        assertEquals("Preheat to 180°C", TemperatureConverter.convert("Preheat to 350 degrees Fahrenheit", TemperatureUnit.CELSIUS))
        assertEquals(YieldKind.MAKES, Servings.kind("Makes 12"))
        assertEquals("https://a.com/r?id=1", UrlCleaner.clean("https://a.com/r?id=1&utm_source=x&fbclid=y"))
        assertTrue(Regex(UnitPatterns.CAPTURED).containsMatchIn("2 cups"))
    }
}
