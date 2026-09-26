package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.SampleRecipe
import com.example.recipeclipper.data.model.Tip
import com.example.recipeclipper.data.model.WelcomeState
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTourPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-run tour's rules (#151) over fakes (iOS: FirstRunTourTests). */
class FirstRunTourTest {

    private val preferences = FakeTourPreferences()
    private val recipes = FakeRecipeRepository()
    private val tour = FirstRunTour(preferences, recipes)

    @Test
    fun `a new user's first plain launch shows the welcome, and it keeps showing until finished`() = runBlocking {
        assertTrue(tour.onLaunch(plain = true))
        assertEquals(WelcomeState.PENDING, preferences.welcome)
        assertTrue("left unfinished, it shows again", tour.onLaunch(plain = true))

        tour.finishWelcome()
        assertEquals(WelcomeState.SEEN, preferences.welcome)
        assertFalse(tour.onLaunch(plain = true))
    }

    @Test
    fun `a first launch from a shared link opens the recipe, and the welcome waits for the next plain launch`() = runBlocking {
        assertFalse(tour.onLaunch(plain = false))
        assertEquals(WelcomeState.PENDING, preferences.welcome)

        // The shared recipe is in the library now, but the user was new when they shared it.
        recipes.count.value = 1
        assertFalse("another share still opens only the recipe", tour.onLaunch(plain = false))
        assertTrue(tour.onLaunch(plain = true))
    }

    @Test
    fun `someone who already has recipes never gets the welcome, nor the recipe and cook mode tips`() = runBlocking {
        recipes.count.value = 12

        assertFalse(tour.onLaunch(plain = true))
        assertEquals(WelcomeState.SEEN, preferences.welcome)
        assertEquals(setOf(Tip.RECIPE, Tip.COOK_MODE), preferences.seenTips.first())
        assertFalse(tour.onLaunch(plain = true))
    }

    @Test
    fun `the sample is added once, in the UI's language, and not again after it is deleted`() = runBlocking {
        tour.addSampleOnce("de")
        assertEquals(listOf("de"), recipes.addSampleCalls.map { it.language })
        assertEquals(SampleRecipe.SOURCE_URL, recipes.addSampleCalls.single().sourceUrl)
        assertTrue(preferences.sampleAdded)

        recipes.sample = null // deleted
        tour.addSampleOnce("de")
        assertEquals(1, recipes.addSampleCalls.size)
        assertNull(recipes.sampleId())
    }

    @Test
    fun `a failed save tries again at the next welcome`() = runBlocking {
        recipes.addSampleResult = null
        tour.addSampleOnce("en")
        assertFalse(preferences.sampleAdded)

        recipes.addSampleResult = 7
        tour.addSampleOnce("en")
        assertTrue(preferences.sampleAdded)
        assertEquals(7L, recipes.sampleId())
    }

    @Test
    fun `Try it opens the sample that is there, and adds it again only if it is gone`() = runBlocking {
        recipes.sample = 5
        assertEquals(5L, tour.sampleToOpen("en"))
        assertTrue(recipes.addSampleCalls.isEmpty())

        recipes.sample = null
        recipes.addSampleResult = 6
        assertEquals(6L, tour.sampleToOpen("fr"))
        assertEquals(listOf("fr"), recipes.addSampleCalls.map { it.language })
    }

    @Test
    fun `showing the tour again brings every tip back`() = runBlocking {
        Tip.entries.forEach { preferences.setTipSeen(it, true) }
        tour.replay()
        assertEquals(emptySet<Tip>(), preferences.seenTips.first())
    }
}
