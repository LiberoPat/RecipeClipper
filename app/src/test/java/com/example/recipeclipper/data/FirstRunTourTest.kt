package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.SampleRecipe
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTourPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-run tour's sample recipe (#151, #190) over fakes (iOS: FirstRunTourTests). */
class FirstRunTourTest {

    private val preferences = FakeTourPreferences()
    private val recipes = FakeRecipeRepository()
    private val tour = FirstRunTour(preferences, recipes)

    @Test
    fun `a new user's first launch adds the sample quietly, in the UI's language`() = runBlocking {
        tour.onLaunch("de")

        assertEquals(listOf("de"), recipes.addSampleCalls.map { it.language })
        assertEquals(SampleRecipe.SOURCE_URL, recipes.addSampleCalls.single().sourceUrl)
        assertTrue(preferences.sampleAdded)
    }

    @Test
    fun `deleted, the sample stays deleted`() = runBlocking {
        tour.onLaunch("en")
        recipes.sample = null // deleted

        tour.onLaunch("en")
        assertEquals(1, recipes.addSampleCalls.size)
        assertNull(recipes.sampleId())
    }

    @Test
    fun `someone who already has recipes never gets the sample`() = runBlocking {
        recipes.count.value = 12

        tour.onLaunch("en")
        assertTrue(recipes.addSampleCalls.isEmpty())
        assertTrue("decided once", preferences.sampleAdded)

        recipes.count.value = 0 // they deleted everything
        tour.onLaunch("en")
        assertTrue(recipes.addSampleCalls.isEmpty())
    }

    @Test
    fun `a sample already there isn't added twice`() = runBlocking {
        recipes.sample = 5
        tour.onLaunch("en")
        assertTrue(recipes.addSampleCalls.isEmpty())
        assertTrue(preferences.sampleAdded)
    }

    @Test
    fun `a failed save tries again at the next launch`() = runBlocking {
        recipes.addSampleResult = null
        tour.onLaunch("en")
        assertFalse(preferences.sampleAdded)

        recipes.addSampleResult = 7
        tour.onLaunch("en")
        assertTrue(preferences.sampleAdded)
        assertEquals(7L, recipes.sampleId())
    }

    @Test
    fun `every launch formats the times of a sample saved before #179`() = runBlocking {
        tour.onLaunch("en")
        tour.onLaunch("en")
        assertEquals(2, recipes.formatSampleTimesCalls)
    }
}
