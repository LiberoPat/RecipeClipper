package com.example.recipeclipper.ui.recipe

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.remote.SharedText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The words around a shared recipe (`RecipeShareText`, #149) in each UI language are the ones
 * `shared/tables/<language>/splitter.json` holds under `shareText`, so a recipe shared from
 * one phone is read back on another (#306, #334). A changed string changes the table too.
 */
@RunWith(AndroidJUnit4::class)
class ShareLabelsTableTest {

    private val locales = mapOf(
        "en" to Locale.ENGLISH, "es" to Locale("es"), "fr" to Locale.FRENCH, "de" to Locale.GERMAN,
        "it" to Locale.ITALIAN, "pt" to Locale("pt", "BR")
    )

    @Test
    fun everyUiLanguagesShareWordsAreInItsTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((language, locale) in locales) {
            val table = SharedText.shareLabels(language) ?: error("$language has no share words in its table")
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val labels = shareLabels(context.createConfigurationContext(configuration).resources)
            val fromStrings = mapOf(
                "ingredients" to labels.ingredients, "instructions" to labels.instructions,
                "serves" to labels.serves(7), "makes" to labels.makes(7),
                "prep" to labels.prep, "cook" to labels.cook, "total" to labels.total
            )
            assertEquals(language, table.mapValues { it.value.replace("{n}", "7") }, fromStrings)
        }
    }
}
