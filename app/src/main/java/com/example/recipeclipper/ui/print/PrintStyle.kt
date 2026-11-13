package com.example.recipeclipper.ui.print

import androidx.core.graphics.toColorInt
import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.recipeclipper.R
import java.util.Locale

/**
 * The printed card's look (#318): the theme's type, Fraunces over Karla, in ink, muted and
 * paprika on white paper, in points (a PDF page is measured in 1/72 inch). Light whatever the
 * phone's theme: it is for paper.
 */
internal class PrintStyle(context: Context, locale: Locale = Locale.getDefault()) {

    /** US Letter in the US and Canada, A4 elsewhere; the print dialog fits it to the paper. */
    val pageWidth = if (locale.country in LETTER_COUNTRIES) 612 else 595
    val pageHeight = if (locale.country in LETTER_COUNTRIES) 792 else 842
    val margin = 54f
    val contentWidth = pageWidth - 2 * margin
    val contentHeight = pageHeight - 2 * margin

    private val display: Typeface = font(context, R.font.fraunces, Typeface.SERIF)
    private val body: Typeface = font(context, R.font.karla, Typeface.SANS_SERIF)

    // The weights the screen uses (Theme.kt's `variable`, iOS's PrintStyle): 500 for headings,
    // 600 for step numbers, set on the variable font through the paint (#331).
    val title = paint(display, 24f, INK, weight = 500)
    val bookTitle = paint(display, 34f, INK, weight = 500)
    val section = paint(display, 15f, PAPRIKA, weight = 500)
    val group = paint(display, 12f, MUTED, weight = 500)
    val stepNumber = paint(display, 11.5f, PAPRIKA, weight = 600)
    val text = paint(body, 11f, INK, weight = 400)
    val muted = paint(body, 10f, MUTED, weight = 400)

    /** [s] wrapped to [width] in [paint], left aligned unless [centre]. */
    fun layout(s: CharSequence, paint: TextPaint, width: Float = contentWidth, centre: Boolean = false): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(if (centre) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()

    private fun paint(face: Typeface, size: Float, color: Int, weight: Int) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        this.color = color
        fontVariationSettings = "'wght' $weight" // ignored by a fallback face with no such axis
    }

    private fun font(context: Context, id: Int, fallback: Typeface): Typeface =
        try {
            ResourcesCompat.getFont(context, id) ?: fallback
        } catch (e: Exception) {
            fallback
        }

    companion object {
        val INK = "#1C1917".toColorInt()
        val MUTED = "#6B6259".toColorInt()
        val PAPRIKA = "#BF4A2B".toColorInt()
        private val LETTER_COUNTRIES = setOf("US", "CA", "MX", "PH")
    }
}
