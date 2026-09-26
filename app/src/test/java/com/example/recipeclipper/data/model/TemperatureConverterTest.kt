package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureConverterTest {

    private fun celsius(text: String) = TemperatureConverter.convert(text, TemperatureUnit.CELSIUS)
    private fun fahrenheit(text: String) = TemperatureConverter.convert(text, TemperatureUnit.FAHRENHEIT)

    @Test fun `as written never changes text`() {
        assertEquals("Bake at 350°F", TemperatureConverter.convert("Bake at 350°F", TemperatureUnit.AS_WRITTEN))
    }

    @Test fun `fahrenheit ovens become the celsius numbers recipes use`() {
        assertEquals("Preheat the oven to 180°C.", celsius("Preheat the oven to 350°F."))
        assertEquals("Bake at 190°C for 20 minutes", celsius("Bake at 375 degrees F for 20 minutes"))
        assertEquals("Bake at 220°C", celsius("Bake at 425°F"))
        assertEquals("Bake at 200°C", celsius("Bake at 400F"))
        assertEquals("Bake at 160°C", celsius("Bake at 325° F"))
    }

    @Test fun `celsius ovens become the fahrenheit numbers recipes use`() {
        assertEquals("Preheat to 350°F", fahrenheit("Preheat to 180°C"))
        assertEquals("Preheat to 400°F", fahrenheit("Preheat to 200°C"))
        assertEquals("Preheat to 425°F", fahrenheit("Preheat to 220°C"))
        assertEquals("Preheat to 325°F", fahrenheit("Preheat to 160 degrees Celsius"))
    }

    @Test fun `celsius option converts independently of unit system`() {
        assertEquals("Bake at 180°C", TemperatureConverter.convert("Bake at 350°F", TemperatureUnit.CELSIUS))
    }

    @Test fun `food and dough temperatures keep degree precision`() {
        assertEquals("Cook to 74°C", celsius("Cook to 165°F"))
        assertEquals("Cook to 63°C", celsius("Cook to 145°F"))
        assertEquals("Use water at 43°C", celsius("Use water at 110°F"))
        assertEquals("Cook to 165°F", fahrenheit("Cook to 74°C"))
    }

    @Test fun `ranges convert both ends`() {
        assertEquals("Bake at 180-190°C", celsius("Bake at 350-375°F"))
        assertEquals("Bake at 180 to 190°C", celsius("Bake at 350 to 375°F"))
    }

    @Test fun `text already in the target scale is left as written`() {
        assertEquals("Bake at 350 F", fahrenheit("Bake at 350 F"))
        assertEquals("Bake at 180 degrees C", celsius("Bake at 180 degrees C"))
    }

    @Test fun `a pair collapses to the half that matches the target`() {
        assertEquals("Preheat to 180°C.", celsius("Preheat to 350°F (180°C)."))
        assertEquals("Preheat to 350°F.", fahrenheit("Preheat to 350°F (180°C)."))
        assertEquals("Preheat to 350°F.", fahrenheit("Preheat to 180°C/350°F."))
        assertEquals("Preheat to 180 degrees C.", celsius("Preheat to 350 degrees F (180 degrees C)."))
    }

    @Test fun `a bare letter with a plausible temperature converts`() {
        assertEquals("Bake at 180°C", celsius("Bake at 350 F"))
        assertEquals("Bake at 350°F", fahrenheit("Bake at 180 C"))
        // A cup written "c." (#135) is never a temperature.
        assertEquals("Stir in 1/2 c. heavy cream", fahrenheit("Stir in 1/2 c. heavy cream"))
    }

    @Test fun `things that are not temperatures are left alone`() {
        assertEquals("Stir in 2 C flour", celsius("Stir in 2 C flour"))
        assertEquals("Stir in 20 C of sugar", celsius("Stir in 20 C of sugar"))
        assertEquals("Bake 25 to 30 minutes", celsius("Bake 25 to 30 minutes"))
        assertEquals("Whisk 1350F", celsius("Whisk 1350F"))
        assertEquals("Add 250 Flakes", celsius("Add 250 Flakes"))
    }

    @Test fun `surrounding text such as fan is kept`() {
        assertEquals("Preheat to 350°F fan", fahrenheit("Preheat to 180°C fan"))
    }

    @Test fun `several temperatures in one step`() {
        assertEquals(
            "Start at 220°C, then lower to 180°C.",
            celsius("Start at 425°F, then lower to 350°F.")
        )
    }
}
