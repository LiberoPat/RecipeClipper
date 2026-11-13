package com.example.recipeclipper.ui.print

import androidx.core.graphics.withClip
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
import coil.size.Scale
import com.example.recipeclipper.data.model.RecipePrint
import com.example.recipeclipper.data.model.RecipePrint.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Draws printed recipes (#318) into a PDF: one recipe for the print dialog, or a list as a
 * cookbook (a title page, contents, each recipe from a new page, numbered pages). Where pages
 * break is [RecipePrint.paginate]'s; this only measures and draws. A photo prints only if it is
 * already cached (never fetched for paper), as the reading view shows it offline, decoded at the
 * band it is drawn in and only while its page is drawn (#331). Everything runs off the main
 * thread, and leaving the screen cancels it between pages.
 */
internal class PrintPdf(private val context: Context) {

    private val style = PrintStyle(context)

    /** One recipe's [blocks] into [out]. */
    suspend fun recipe(blocks: List<Block>, out: File) = write(out) { doc ->
        pages(measure(blocks, style, ::cached)).forEachIndexed { i, page -> drawPage(doc, i + 1, page, footer = null) }
    }

    /** A cookbook named [title] of [recipes] (each name with its blocks), contents headed [contents]. */
    suspend fun cookbook(title: String, contents: String, recipes: List<Pair<String, List<Block>>>, out: File) = write(out) { doc ->
        val pagesOf = recipes.map { (_, blocks) -> pages(measure(blocks, style, ::cached)).ifEmpty { listOf(emptyList()) } }
        val starts = IntArray(recipes.size)
        val rows = listOf(heading(contents)) + recipes.mapIndexed { i, (name, _) -> contentsRow(name) { starts[i] } }
        val contentsPages = pages(rows)
        RecipePrint.startPages(pagesOf.map { it.size }, contentsPages.size).forEachIndexed { i, start -> starts[i] = start }

        doc.page(1) { page ->
            val name = style.layout(title, style.bookTitle, centre = true)
            page.canvas.withTranslation(style.margin, (style.pageHeight - name.height) / 2f) { name.draw(this) }
        }
        var number = 2
        contentsPages.forEach { drawPage(doc, number, it, footer = number++.toString()) }
        pagesOf.flatten().forEach { drawPage(doc, number, it, footer = number++.toString()) }
    }

    private fun heading(text: String) = style.layout(text, style.title).let { layout ->
        LaidBlock(0f, listOf(layout.height + 12f), keep = true) { canvas, top, _, _, _ ->
            canvas.withTranslation(style.margin, top) { layout.draw(this) }
        }
    }

    private fun contentsRow(name: String, page: () -> Int) = style.layout(name, style.text, style.contentWidth - 40f).let { layout ->
        LaidBlock(8f, listOf(layout.height.toFloat()), keep = false) { canvas, top, _, _, _ ->
            canvas.withTranslation(style.margin, top) { layout.draw(this) }
            val number = Paint(style.muted).apply { textAlign = Paint.Align.RIGHT }
            canvas.drawText(page().toString(), style.margin + style.contentWidth, top + layout.getLineBaseline(0), number)
        }
    }

    /** A block, or the lines of one, on a page. */
    private data class Placed(val block: LaidBlock, val piece: RecipePrint.Piece)

    private fun pages(laid: List<LaidBlock>): List<List<Placed>> =
        RecipePrint.paginate(laid.map { it.measured() }, style.contentHeight.toDouble())
            .groupBy { it.page }.toSortedMap().values.map { page -> page.map { Placed(laid[it.block], it) } }

    private suspend fun drawPage(doc: PdfDocument, number: Int, placed: List<Placed>, footer: String?) {
        currentCoroutineContext().ensureActive()
        // This page's pictures first: nothing suspends while a page is open, and only one page's are held.
        val photos = placed.mapNotNull { it.block.photoUrl }.distinct().associateWith { load(it) }
        doc.page(number) { page ->
            var y = style.margin
            page.canvas.withClip(0f, style.margin, style.pageWidth.toFloat(), style.margin + style.contentHeight) {
                placed.forEachIndexed { i, (block, piece) ->
                    if (i > 0 && piece.first == 0) y += block.space
                    block.draw(this, y, piece.first, piece.end, photos[block.photoUrl])
                    y += block.height(piece.first, piece.end)
                }
            }
            if (footer != null) {
                val paint = Paint(style.muted).apply { textAlign = Paint.Align.CENTER }
                page.canvas.drawText(footer, style.pageWidth / 2f, style.pageHeight - style.margin / 2f, paint)
            }
        }
    }

    /** Page [number], drawn by [draw] and always finished, so [PdfDocument.close] never finds one open. */
    private inline fun PdfDocument.page(number: Int, draw: (PdfDocument.Page) -> Unit) {
        val page = startPage(PdfDocument.PageInfo.Builder(style.pageWidth, style.pageHeight, number).create())
        try {
            draw(page)
        } finally {
            finishPage(page)
        }
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

    /** Whether Coil has [url] on disk, without decoding it (a picture is never fetched for paper). */
    private fun cached(url: String): Boolean = context.imageLoader.diskCache?.openSnapshot(url)?.use { true } == true

    /** [url] from Coil's disk cache, decoded at the photo band's size and cropped to it. */
    private suspend fun load(url: String): Bitmap? {
        val width = (style.contentWidth * PHOTO_SCALE).roundToInt()
        val height = (photoHeight(style.contentWidth) * PHOTO_SCALE).roundToInt()
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(width, height)
            .scale(Scale.FILL)
            .precision(Precision.EXACT)
            .allowHardware(false) // a PDF canvas draws only software bitmaps
            .networkCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(CachePolicy.DISABLED) // the band is drawn once; the screen's copy is another size
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        // Only the band's pixels go into the file: the overhang of a taller or wider picture is cut off.
        val w = minOf(width, bitmap.width)
        val h = minOf(height, bitmap.height)
        return if (w == bitmap.width && h == bitmap.height) bitmap
        else Bitmap.createBitmap(bitmap, (bitmap.width - w) / 2, (bitmap.height - h) / 2, w, h)
    }

    private companion object {
        /** Pixels per point in the photo band: 144 dpi on paper. */
        const val PHOTO_SCALE = 2f
    }
}
