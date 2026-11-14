package com.example.recipeclipper.ui.print

import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import com.example.recipeclipper.data.model.RecipePrint
import com.example.recipeclipper.data.model.RecipePrint.Block

/**
 * One [Block] measured for the page: [space] above it (dropped at the top of a page), the
 * heights of its [lines] (a photo is one), and how to [draw] lines `first` until `end` of it
 * with the first's top at a given y: a block taller than a page runs over several
 * ([RecipePrint.paginate]). A photo's [photoUrl] is loaded just before its page is drawn (one
 * page's pictures at a time, #331) and handed to [draw].
 */
internal class LaidBlock(
    val space: Float,
    val lines: List<Float>,
    val keep: Boolean,
    val photoUrl: String? = null,
    val draw: (canvas: Canvas, top: Float, first: Int, end: Int, photo: Bitmap?) -> Unit
) {
    /** The height of lines [first] until [end]. */
    fun height(first: Int, end: Int): Float = lines.subList(first, end).sum()

    fun measured() = RecipePrint.Measured(space.toDouble(), lines.map { it.toDouble() }, keep)
}

/** [blocks] measured in [style]; a photo only when [cached] says it's on the phone. */
internal fun measure(blocks: List<Block>, style: PrintStyle, cached: (String) -> Boolean): List<LaidBlock> =
    blocks.mapNotNull { block ->
        val keep = RecipePrint.keepsWithNext(block)
        when (block) {
            is Block.Photo -> if (!cached(block.url)) null else photo(block.url, style)
            is Block.Title -> text(style.layout(block.text, style.title), 0f, keep, style)
            is Block.Credit -> text(style.layout(block.text, style.muted), 4f, keep, style)
            is Block.Meta -> text(style.layout(block.text, style.muted), 6f, keep, style)
            is Block.Section -> text(style.layout(block.text, style.section), 18f, keep, style)
            is Block.GroupHeading -> text(style.layout(block.text, style.group), 8f, keep, style)
            is Block.Ingredient -> hanging("•", style.layout(block.text, style.text, style.contentWidth - 14f), 14f, 4f, style, false)
            is Block.Step -> hanging(
                "${block.number}.", style.layout(block.text, style.text, style.contentWidth - 22f), 22f, 8f, style, true
            )
            is Block.Note -> text(style.layout(block.text, style.text), 4f, keep, style)
        }
    }

private fun text(layout: StaticLayout, space: Float, keep: Boolean, style: PrintStyle) =
    LaidBlock(space, lineHeights(layout), keep) { canvas, top, first, end, _ ->
        drawLines(canvas, layout, style.margin, top, first, end)
    }

/** A line with a marker in the margin ("•" or "3.") beside its first line, the text indented by [indent]. */
private fun hanging(marker: String, layout: StaticLayout, indent: Float, space: Float, style: PrintStyle, numbered: Boolean) =
    LaidBlock(space, lineHeights(layout), keep = false) { canvas, top, first, end, _ ->
        if (first == 0) canvas.drawText(marker, style.margin, top + layout.getLineBaseline(0), if (numbered) style.stepNumber else style.muted)
        drawLines(canvas, layout, style.margin + indent, top, first, end)
    }

private fun lineHeights(layout: StaticLayout): List<Float> =
    (0 until layout.lineCount).map { (layout.getLineTop(it + 1) - layout.getLineTop(it)).toFloat() }

/** Lines [first] until [end] of [layout] at [x], the first's top at [top]; the other lines are clipped away. */
private fun drawLines(canvas: Canvas, layout: StaticLayout, x: Float, top: Float, first: Int, end: Int) {
    if (end <= first) return
    val lineTop = layout.getLineTop(first).toFloat()
    canvas.withTranslation(x, top - lineTop) {
        withClip(-x, lineTop, layout.width + x, layout.getLineTop(end).toFloat()) { layout.draw(this) }
    }
}

/** The photo across the page, already cropped to a band at most [MAX_PHOTO] tall ([PrintPdf.photoBand]). */
private fun photo(url: String, style: PrintStyle): LaidBlock {
    val height = photoHeight(style.contentWidth)
    return LaidBlock(0f, listOf(height + 14f), keep = true, photoUrl = url) { canvas, top, _, _, bitmap ->
        bitmap ?: return@LaidBlock
        val box = RectF(style.margin, top, style.margin + style.contentWidth, top + height)
        canvas.drawBitmap(bitmap, null, box, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }
}

/** The photo band's height in points, for [width] across. */
internal fun photoHeight(width: Float): Float = minOf(width * 9f / 16f, MAX_PHOTO)

private const val MAX_PHOTO = 230f
