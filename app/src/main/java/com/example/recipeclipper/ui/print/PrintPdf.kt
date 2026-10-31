package com.example.recipeclipper.ui.print

import androidx.core.graphics.withTranslation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfDocument
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.example.recipeclipper.data.model.RecipePrint
import com.example.recipeclipper.data.model.RecipePrint.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Draws printed recipes (#318) into a PDF: one recipe for the print dialog, or a list as a
 * cookbook (a title page, contents, each recipe from a new page, numbered pages). Where pages
 * break is [RecipePrint.paginate]'s; this only measures and draws. A photo prints only if it is
 * already cached (never fetched for paper), as the reading view shows it offline.
 */
internal class PrintPdf(private val context: Context) {

    private val style = PrintStyle(context)

    /** One recipe's [blocks] into [out]. */
    suspend fun recipe(blocks: List<Block>, out: File) = write(out) { doc ->
        paged(measure(blocks, style, ::cached)).forEachIndexed { i, page -> drawPage(doc, i + 1, page, footer = null) }
    }

    /** A cookbook named [title] of [recipes] (each name with its blocks), contents headed [contents]. */
    suspend fun cookbook(title: String, contents: String, recipes: List<Pair<String, List<Block>>>, out: File) = write(out) { doc ->
        val pagesOf = recipes.map { (_, blocks) -> paged(measure(blocks, style, ::cached)).ifEmpty { listOf(emptyList()) } }
        val starts = IntArray(recipes.size)
        val rows = listOf(heading(contents)) + recipes.mapIndexed { i, (name, _) -> contentsRow(name) { starts[i] } }
        val contentsPages = paged(rows)
        RecipePrint.startPages(pagesOf.map { it.size }, contentsPages.size).forEachIndexed { i, start -> starts[i] = start }

        val page = doc.startPage(PdfDocument.PageInfo.Builder(style.pageWidth, style.pageHeight, 1).create())
        val name = style.layout(title, style.bookTitle, centre = true)
        page.canvas.withTranslation(style.margin, (style.pageHeight - name.height) / 2f) { name.draw(this) }
        doc.finishPage(page)
        var number = 2
        contentsPages.forEach { drawPage(doc, number, it, footer = number++.toString()) }
        pagesOf.flatten().forEach { drawPage(doc, number, it, footer = number++.toString()) }
    }

    private fun heading(text: String) = style.layout(text, style.title).let { layout ->
        LaidBlock(0f, layout.height + 12f, keep = true) { canvas, top, _ ->
            canvas.withTranslation(style.margin, top) { layout.draw(this) }
        }
    }

    private fun contentsRow(name: String, page: () -> Int) = style.layout(name, style.text, style.contentWidth - 40f).let { layout ->
        LaidBlock(8f, layout.height.toFloat(), keep = false) { canvas, top, _ ->
            canvas.withTranslation(style.margin, top) { layout.draw(this) }
            val number = Paint(style.muted).apply { textAlign = Paint.Align.RIGHT }
            canvas.drawText(page().toString(), style.margin + style.contentWidth, top + layout.getLineBaseline(0), number)
        }
    }

    private fun paged(laid: List<LaidBlock>): List<List<LaidBlock>> {
        val pages = RecipePrint.paginate(
            laid.map { (it.space + it.height).toDouble() }, laid.map { it.keep }, style.contentHeight.toDouble()
        )
        return laid.indices.groupBy { pages[it] }.toSortedMap().values.map { indices -> indices.map(laid::get) }
    }

    private suspend fun drawPage(doc: PdfDocument, number: Int, blocks: List<LaidBlock>, footer: String?) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(style.pageWidth, style.pageHeight, number).create())
        var y = style.margin
        blocks.forEachIndexed { i, block ->
            if (i > 0) y += block.space
            block.draw(page.canvas, y, block.photoUrl?.let { load(it) })
            y += block.height
        }
        if (footer != null) {
            val paint = Paint(style.muted).apply { textAlign = Paint.Align.CENTER }
            page.canvas.drawText(footer, style.pageWidth / 2f, style.pageHeight - style.margin / 2f, paint)
        }
        doc.finishPage(page)
    }

    private suspend fun write(out: File, draw: suspend (PdfDocument) -> Unit): Boolean = withContext(Dispatchers.Default) {
        val doc = PdfDocument()
        try {
            draw(doc)
            out.parentFile?.mkdirs()
            out.outputStream().use { doc.writeTo(it) }
            true
        } finally {
            doc.close()
        }
    }

    private suspend fun cached(url: String): Boolean = load(url) != null

    // From Coil's caches only: printing never goes to the network for a picture.
    private suspend fun load(url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(PHOTO_PX)
            .precision(Precision.INEXACT)
            .allowHardware(false) // a PDF canvas draws only software bitmaps
            .networkCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        return (result.drawable as? BitmapDrawable)?.bitmap
    }

    private companion object {
        const val PHOTO_PX = 1000
    }
}
