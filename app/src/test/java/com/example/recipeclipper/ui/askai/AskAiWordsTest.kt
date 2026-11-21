package com.example.recipeclipper.ui.askai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.AskAiPrompt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The prompt's English words in `strings.xml` are the ones [AskAiPrompt.Words.ENGLISH] (and the
 * core tests) are written with: the two cannot drift apart.
 */
@RunWith(AndroidJUnit4::class)
class AskAiWordsTest {

    @Test fun `the English resources are the English words`() {
        val words = askAiWords(ApplicationProvider.getApplicationContext<Context>().resources)
        val english = AskAiPrompt.Words.ENGLISH
        assertEquals(english.language, words.language)
        for (day in 1..7) assertEquals(english.weekday(day), words.weekday(day))
        assertEquals(english.wantToCook("x"), words.wantToCook("x"))
        assertEquals(english.changeThis("x"), words.changeThis("x"))
        assertEquals(english.recipeBelow, words.recipeBelow)
        assertEquals(english.whatCanIMake, words.whatCanIMake)
        assertEquals(english.useUpFirst("x"), words.useUpFirst("x"))
        assertEquals(english.inPantry("x"), words.inPantry("x"))
        assertEquals(english.planIntro, words.planIntro)
        assertEquals(english.favourites("x"), words.favourites("x"))
        assertEquals(english.cookedLately("x"), words.cookedLately("x"))
        assertEquals(english.alsoWish("x"), words.alsoWish("x"))
        assertEquals(english.replyOne, words.replyOne)
        assertEquals(english.replyPlan, words.replyPlan)
        assertEquals(english.replyChanged, words.replyChanged)
        assertEquals(english.recipeFields, words.recipeFields)
        assertEquals(english.unitsAsWritten, words.unitsAsWritten)
        assertEquals(english.unitsMetric, words.unitsMetric)
        assertEquals(english.unitsOunces, words.unitsOunces)
        assertEquals(english.temperatureCelsius, words.temperatureCelsius)
        assertEquals(english.temperatureFahrenheit, words.temperatureFahrenheit)
        assertEquals(english.writeIn("x"), words.writeIn("x"))
        assertEquals(english.noteOutside, words.noteOutside)
        assertEquals(english.planMealsIntro("Lunch, Dinner"), words.planMealsIntro("Lunch, Dinner"))
        assertEquals(english.savedRecipesIntro, words.savedRecipesIntro)
        assertEquals(english.useSavedOnly, words.useSavedOnly)
        assertEquals(english.useSavedMix(3), words.useSavedMix(3))
        assertEquals(english.replyPlanMeals, words.replyPlanMeals)
    }
}
