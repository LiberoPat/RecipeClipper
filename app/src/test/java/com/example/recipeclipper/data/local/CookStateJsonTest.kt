package com.example.recipeclipper.data.local

import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.SavedTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CookStateJsonTest {

    @Test fun `round trips every field`() {
        val progress = CookProgress(
            active = true,
            currentStep = 2,
            doneSteps = setOf(0, 1),
            timers = mapOf(
                1 to SavedTimer(600, 600, endsAt = 1_700_000_000_000),
                3 to SavedTimer(300, 120, endsAt = null)
            )
        )
        assertEquals(progress, CookStateJson.decode(CookStateJson.encode(progress)))
    }

    @Test fun `an empty cook state is stored as null`() {
        assertNull(CookStateJson.encode(CookProgress()))
        assertEquals(CookProgress(), CookStateJson.decode(null))
    }

    @Test fun `unreadable text decodes to an empty cook state rather than throwing`() {
        assertEquals(CookProgress(), CookStateJson.decode("not json"))
        assertEquals(CookProgress(), CookStateJson.decode("""{"timers":[{"step":"x"}]}"""))
    }

    @Test fun `a paused timer has no deadline in the json`() {
        val json = CookStateJson.encode(CookProgress(timers = mapOf(0 to SavedTimer(60, 30, null))))!!
        assertEquals(false, json.contains("endsAt"))
    }
}
