package com.example.recipeclipper

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.TextIntent.Target
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Text shared into the app (#306): where [TextIntent] sends it, with the shareText flag on and off. */
@RunWith(AndroidJUnit4::class)
class TextIntentTest {

    private val recipe = "Ingredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix.\n2. Bake."

    private fun resolvesTo(intent: Intent, activity: Class<*>): Boolean {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.packageManager.queryIntentActivities(intent.setPackage(context.packageName), 0)
            .any { it.activityInfo.name == activity.name }
    }

    @Test
    fun selectedTextReachesTheTrampolineAndIsReadOnlyWithTheFlagOn() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, recipe as CharSequence)

        // The trampoline (#334), never MainActivity itself, is what other apps' menus start.
        assertTrue(resolvesTo(intent, ProcessTextActivity::class.java))
        assertFalse(resolvesTo(intent, MainActivity::class.java))
        assertEquals(recipe, TextIntent.text(intent, shareTextOn = true))
        assertNull(TextIntent.text(intent, shareTextOn = false))
    }

    @Test
    fun sharedTextIsReadEitherWay() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, recipe)

        assertEquals(recipe, TextIntent.text(intent, shareTextOn = false))
        assertEquals(recipe, TextIntent.text(intent, shareTextOn = true))
    }

    @Test
    fun aRecipeOpensTheEditorEvenBesideALink() {
        val text = "$recipe\nFrom https://example.com/scones"
        val target = TextIntent.target(text, shareTextOn = true) as Target.Review
        assertEquals(text, target.text)
        assertTrue(target.reading.sorted)
        assertEquals(listOf("From https://example.com/scones"), target.reading.leftovers)
        // Off, as before #306: the link imports.
        assertEquals(Target.Open(Routes.import("https://example.com/scones")), TextIntent.target(text, shareTextOn = false))
    }

    @Test
    fun aWeekOfRecipesOpensTheMealPlanReviewOnlyWithItsFlagOn() {
        fun meal(name: String, day: String) = """{"@type": "Recipe", "name": "$name", "day": "$day", """ +
            """"recipeIngredient": ["1 egg"], "recipeInstructions": ["Cook."]}"""
        val week = "```json\n[${meal("Omelette", "2026-11-23")}, ${meal("Soup", "2026-11-24")}]\n```"
        val plan = TextIntent.target(week, shareTextOn = true, mealPlanOn = true) as Target.Plan
        assertEquals(listOf("Omelette", "Soup"), plan.plan.items.map { it.recipe.name })
        // Flag off: the first recipe opens the editor, as before (#350).
        assertTrue(TextIntent.target(week, shareTextOn = true, mealPlanOn = false) is Target.Review)
        // One recipe is the editor's, with the flag on.
        assertTrue(TextIntent.target(meal("Omelette", "2026-11-23"), shareTextOn = true, mealPlanOn = true) is Target.Review)
        // Share text off: as before #306, nothing here applies.
        assertTrue(TextIntent.target(week, shareTextOn = false, mealPlanOn = true) !is Target.Plan)
    }

    @Test
    fun anAnswerCutOffBeforeItsFirstRecipeEndedIsACauseNotAFragmentInTheEditor() {
        val cut = "```json\n[\n  {\n    \"@type\": \"Recipe\",\n    \"name\": \"Mon\",\n    \"day\": \"2026-11-19\",\n" +
            "    \"recipeInstructions\": [\n      { \"@type\": \"HowToStep\","
        assertEquals(Target.Failed(ParseError.CutOff), TextIntent.target(cut, shareTextOn = true, mealPlanOn = true))
        assertEquals(Target.Failed(ParseError.CutOff), TextIntent.target(cut, shareTextOn = true, mealPlanOn = false))
        // A cut-off week with whole recipes before it is still a plan, flagged.
        val meal = """{"@type": "Recipe", "name": "Omelette", "day": "2026-11-23", "recipeIngredient": ["1 egg"], "recipeInstructions": ["Cook."]}"""
        val plan = TextIntent.target("[$meal, ${meal.replace("Omelette", "Soup")}, {\"@type\": \"Recipe\", \"name\": \"X\", \"re", shareTextOn = true, mealPlanOn = true) as Target.Plan
        assertTrue(plan.plan.cutOff)
        // Not JSON, or whole: as before.
        assertTrue(TextIntent.target("milk\neggs [see below", shareTextOn = true) is Target.Review)
        assertTrue(TextIntent.target(meal, shareTextOn = true, mealPlanOn = true) is Target.Review)
    }

    @Test
    fun aLinkImportsAndAListIsOffered() {
        val link = "Look! https://example.com/scones"
        assertEquals(Target.Open(Routes.import("https://example.com/scones")), TextIntent.target(link, shareTextOn = true))
        val list = "Groceries\n- milk\n- eggs"
        assertEquals(Target.OfferList(list), TextIntent.target(list, shareTextOn = true))
    }

    @Test
    fun proseWithALinkAndAMixedListOpenTheEditorAsWritten() {
        val prose = "Hi Mum,\nMix the flour with the butter, then bake it.\nLove, Sam\nSent from https://example.com/x"
        val review = TextIntent.target(prose, shareTextOn = true) as Target.Review
        assertFalse(review.reading.sorted)
        val mixed = "Here's grandma's scones…\n- 2 cups flour\n- 1 tsp salt\nMix everything and bake 20 minutes."
        assertTrue(TextIntent.target(mixed, shareTextOn = true) is Target.Review)
    }

    @Test
    fun otherTextOpensTheEditorWithTheFlagOnAndIsAListWithItOff() {
        val text = "milk\neggs"
        assertTrue(TextIntent.target(text, shareTextOn = true) is Target.Review)
        assertEquals(Target.OfferList(text), TextIntent.target(text, shareTextOn = false))
        assertNull(TextIntent.target("   ", shareTextOn = true))
        assertNull(TextIntent.target("   ", shareTextOn = false))
    }

    @Test
    fun besidePicturesOnlyARecipeWins() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://photos/1"))
            .putExtra(Intent.EXTRA_TEXT, recipe)
        assertEquals(recipe, TextIntent.text(intent, shareTextOn = true))
        assertNull(TextIntent.text(intent, shareTextOn = false))

        assertTrue(TextIntent.target(recipe, shareTextOn = true, withPictures = true) is Target.Review)
        // A caption, a link or a list beside a picture leaves the picture to the scan.
        assertNull(TextIntent.target("Dinner last night", shareTextOn = true, withPictures = true))
        assertNull(TextIntent.target("https://example.com/scones", shareTextOn = true, withPictures = true))
        assertNull(TextIntent.target("- milk\n- eggs", shareTextOn = true, withPictures = true))
    }
}
