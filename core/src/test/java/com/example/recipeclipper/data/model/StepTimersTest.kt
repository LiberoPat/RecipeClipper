package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepTimersTest {

    @Test fun `a stated duration becomes a timer`() {
        assertEquals(1200, StepTimers.parse("Simmer for 20 minutes."))
        assertEquals(30, StepTimers.parse("Stir for 30 seconds."))
        assertEquals(7200, StepTimers.parse("Roast for 2 hours."))
        assertEquals(600, StepTimers.parse("Rest for 10 mins."))
        assertEquals(300, StepTimers.parse("Add 1 c. heavy cream and simmer 5 minutes."))
    }

    @Test fun `a range uses its lower bound`() {
        assertEquals(1500, StepTimers.parse("Bake 25 to 30 minutes."))
        assertEquals(3600, StepTimers.parse("Chill for 1-2 hours."))
    }

    @Test fun `fractions and compound durations add up`() {
        assertEquals(5400, StepTimers.parse("Cook for 1 1/2 hours."))
        assertEquals(5400, StepTimers.parse("Braise for 1 hour 30 minutes."))
        assertEquals(150, StepTimers.parse("Boil 2 minutes and 30 seconds."))
    }

    @Test fun `an adjective form still counts`() {
        assertEquals(1200, StepTimers.parse("Let it go for a 20-minute simmer."))
    }

    @Test fun `only the first duration in a step is used`() {
        assertEquals(300, StepTimers.parse("Sear 5 minutes per side, then rest 10 minutes."))
    }

    @Test fun `steps without a stated time get no timer`() {
        assertNull(StepTimers.parse("Whisk until smooth."))
        assertNull(StepTimers.parse("Heat the oven to 350°F."))
        assertNull(StepTimers.parse("Add 2 cups of flour and mix."))
        assertNull(StepTimers.parse("Add 2 T. butter and 1 t salt."))
        assertNull(StepTimers.parse("Mince the garlic finely."))
        assertNull(StepTimers.parse(""))
    }

    @Test fun `clock formatting`() {
        assertEquals("20:00", StepTimers.clock(1200))
        assertEquals("0:05", StepTimers.clock(5))
        assertEquals("1:05:00", StepTimers.clock(3900))
        assertEquals("0:00", StepTimers.clock(-3))
    }

    @Test fun `button label wording`() {
        assertEquals("20 min", StepTimers.label(1200))
        assertEquals("1 hr 30 min", StepTimers.label(5400))
        assertEquals("2 hr", StepTimers.label(7200))
        assertEquals("45 sec", StepTimers.label(45))
        assertEquals("2 min 30 sec", StepTimers.label(150))
    }

    @Test fun `a decimal comma duration is read as a decimal`() {
        assertEquals(5400, StepTimers.parse("Bake for 1,5 hours."))
        assertEquals(150, StepTimers.parse("Simmer 2,5 minutes"))
        assertNull(StepTimers.parse("Rest for 1,500 seconds"))
    }

    @Test fun `a mixed number with and or a fraction slash is read whole`() {
        // Once "1 and 1/2 hours" gave a 30-minute timer, from the "1/2 hours" alone.
        assertEquals(5400, StepTimers.parse("Roast for 1 and 1/2 hours."))
        assertEquals(1800, StepTimers.parse("Rest for 1⁄2 hour."))
    }
}
