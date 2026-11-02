package com.example.recipeclipper.ui.print

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.recipeclipper.data.model.RecipePrint.Block
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The PDF itself (#318), which only a device can draw (Robolectric has no PDF engine): a recipe
 * long enough to break across pages, and a cookbook's title page, contents and recipes.
 */
@RunWith(AndroidJUnit4::class)
class PrintPdfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "print-test").apply { deleteRecursively(); mkdirs() }

    private fun card(name: String, steps: Int) = listOf(Block.Title(name), Block.Credit("example.com"), Block.Meta("Serves 4")) +
        Block.Section("Ingredients") + (1..6).map { Block.Ingredient("$it cups flour") } +
        Block.Section("Instructions") + (1..steps).map { Block.Step(it, "Stir the batter well, then rest it a while. ".repeat(4)) }

    private fun pages(file: File): Int =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }

    @Test
    fun aShortRecipeIsOnePage() = runBlocking {
        val file = File(dir, "short.pdf")
        assertTrue(PrintPdf(context).recipe(card("Pancakes", steps = 3), file))
        assertEquals(1, pages(file))
    }

    @Test
    fun aLongRecipeBreaksAcrossPages() = runBlocking {
        val file = File(dir, "long.pdf")
        assertTrue(PrintPdf(context).recipe(card("Pancakes", steps = 40), file))
        assertTrue(pages(file) > 1)
    }

    @Test
    fun aCookbookHasATitlePageContentsAndEachRecipeFromANewPage() = runBlocking {
        val file = File(dir, "book.pdf")
        val recipes = listOf("Pancakes" to card("Pancakes", 3), "Waffles" to card("Waffles", 3))
        assertTrue(PrintPdf(context).cookbook("Breakfast", "Contents", recipes, file))
        assertEquals(4, pages(file))
    }
}
