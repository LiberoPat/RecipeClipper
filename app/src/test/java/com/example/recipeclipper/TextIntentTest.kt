package com.example.recipeclipper

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.TextIntent.Target
import com.example.recipeclipper.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Text shared into the app (#306): where [TextIntent] sends it, with the shareText flag on and off. */
@RunWith(AndroidJUnit4::class)
class TextIntentTest {

    private val recipe = "Ingredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix.\n2. Bake."

    private fun resolvesToMainActivity(intent: Intent): Boolean {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.packageManager.queryIntentActivities(intent.setPackage(context.packageName), 0)
            .any { it.activityInfo.name == MainActivity::class.java.name }
    }

    @Test
    fun selectedTextIsReadOnlyWithTheFlagOn() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, recipe as CharSequence)

        assertTrue(resolvesToMainActivity(intent))
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
        assertEquals(Target.Open(Routes.editText(text)), TextIntent.target(text, shareTextOn = true))
        // Off, as before #306: the link imports.
        assertEquals(Target.Open(Routes.import("https://example.com/scones")), TextIntent.target(text, shareTextOn = false))
    }

    @Test
    fun aLinkImportsAndAListIsOffered() {
        val link = "Look! https://example.com/scones"
        assertEquals(Target.Open(Routes.import("https://example.com/scones")), TextIntent.target(link, shareTextOn = true))
        val list = "Groceries\n- milk\n- eggs"
        assertEquals(Target.OfferList(list), TextIntent.target(list, shareTextOn = true))
    }

    @Test
    fun otherTextOpensTheEditorWithTheFlagOnAndIsAListWithItOff() {
        val text = "milk\neggs"
        assertEquals(Target.Open(Routes.editText(text)), TextIntent.target(text, shareTextOn = true))
        assertEquals(Target.OfferList(text), TextIntent.target(text, shareTextOn = false))
        assertNull(TextIntent.target("   ", shareTextOn = true))
    }
}
