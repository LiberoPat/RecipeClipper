package com.example.recipeclipper.ui.print

import androidx.core.graphics.withTranslation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.StaticLayout
import com.example.recipeclipper.data.model.RecipePrint
import com.example.recipeclipper.data.model.RecipePrint.Block

/**
 * One [Block] measured for the page: [space] above it (dropped at the top of a page), its own
 * [height], and how to draw it with its top at a given y. A photo is drawn from [photoUrl].
 */
internal class LaidBlock(
    val space: Float,
    val height: Float,
    val keep: Boolean,
    val photoUrl: String? = null,
    val draw: (Canvas, Float, Bitmap?) -> Unit
)

/** [blocks] measured in [style]; a photo only when [cached] says it's on the phone. */
internal suspend fun measure(blocks: List<Block>, style: PrintStyle, cached: suspend (String) -> Boolean): List<LaidBlock> =
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
    LaidBlock(space, layout.height.toFloat(), keep) { canvas, top, _ ->
        canvas.withTranslation(style.margin, top) { layout.draw(this) }
    }

/** A line with a marker in the margin ("•" or "3."), the text indented by [indent]. */
private fun hanging(marker: String, layout: StaticLayout, indent: Float, space: Float, style: PrintStyle, numbered: Boolean) =
    LaidBlock(space, layout.height.toFloat(), keep = false) { canvas, top, _ ->
        val paint = if (numbered) style.stepNumber else style.muted
        canvas.drawText(marker, style.margin, top + layout.getLineBaseline(0), paint)
        canvas.withTranslation(style.margin + indent, top) { layout.draw(this) }
    }

/** The photo across the page, cropped from its centre to a band at most [MAX_PHOTO] tall. */
private fun photo(url: String, style: PrintStyle): LaidBlock {
    val height = minOf(style.contentWidth * 9f / 16f, MAX_PHOTO)
    return LaidBlock(0f, height + 14f, keep = true, photoUrl = url) { canvas, top, bitmap ->
        bitmap ?: return@LaidBlock
        val box = RectF(style.margin, top, style.margin + style.contentWidth, top + height)
        val scale = maxOf(box.width() / bitmap.width, box.height() / bitmap.height)
        val w = (box.width() / scale).toInt()
        val h = (box.height() / scale).toInt()
        val src = Rect((bitmap.width - w) / 2, (bitmap.height - h) / 2, (bitmap.width + w) / 2, (bitmap.height + h) / 2)
        canvas.drawBitmap(bitmap, src, box, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }
}

private const val MAX_PHOTO = 230f
