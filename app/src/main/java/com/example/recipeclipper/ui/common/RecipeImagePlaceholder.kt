package com.example.recipeclipper.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.NoPhoto
import com.example.recipeclipper.data.model.NoPhotoSource
import com.example.recipeclipper.ui.theme.Fraunces
import com.example.recipeclipper.ui.theme.LocalNoPhotoTints

/** How a recipe with no photo is drawn (#403). [Auto] picks by the size it is given. */
enum class NoPhotoStyle { Auto, Monogram, Label }

/** Up to this size (the shorter side) an image is lettering: the title's first letter. */
internal val MonogramMaxSize: Dp = 64.dp

/** A placeholder this much wider than tall is a band or a banner: its label is set left, low. */
private const val BAND_RATIO = 1.5f

/** The most names a band or a banner shows; a square shows the first. */
private const val BAND_LABELS = 3

/** True with the `noPhotoImage` flag on (#403); off, every image slot is as it was. */
@Composable
fun noPhotoImageOn(): Boolean = LocalFlagValues.current.isOn(Flag.NO_PHOTO_IMAGE)

/**
 * The designed image of a recipe with no photo (#403): never a stock image, only the recipe's
 * own words on one of five warm tints chosen by its uid. A small surface ([MonogramMaxSize] and
 * below, by the size it is drawn at) gets the title's first letter; a larger one the recipe's
 * first ingredient names in italic (the first only in a square cover or cell, up to three in a
 * band or a banner), or the letter when it has no ingredient lines. A title with no letter and
 * no names is a plain tint tile.
 *
 * Decorative: it adds no semantics, so what a screen announces is what it announced before.
 * With the flag off it is [whenOff], today's blank tile. The caller clips and sizes it.
 */
@Composable
fun RecipeImagePlaceholder(
    recipe: NoPhotoSource,
    modifier: Modifier = Modifier,
    style: NoPhotoStyle = NoPhotoStyle.Auto,
    maxLabelLines: Int = 2,
    whenOff: @Composable (Modifier) -> Unit = { Box(it.background(MaterialTheme.colorScheme.outlineVariant)) }
) {
    if (!noPhotoImageOn()) {
        whenOff(modifier)
        return
    }
    BoxWithConstraints(modifier) {
        val width = maxWidth
        val height = if (constraints.hasBoundedHeight) maxHeight else maxWidth
        val small = minOf(width, height) <= MonogramMaxSize
        val band = width.value >= height.value * BAND_RATIO
        val wantsLabel = when (style) {
            NoPhotoStyle.Monogram -> false
            NoPhotoStyle.Label -> true
            NoPhotoStyle.Auto -> !small
        }
        val art = remember(recipe, wantsLabel, band) {
            NoPhoto.art(recipe, if (!wantsLabel) 0 else if (band) BAND_LABELS else 1)
        }
        val tint = LocalNoPhotoTints.current.let { it[art.tint % it.size] }
        // Which one was drawn is for the tests; the semantics are cleared, so a screen reader
        // announces nothing more than it did before.
        val drawn = if (art.labels.isNotEmpty()) "noPhoto.label" else if (art.initial != null) "noPhoto.monogram" else "noPhoto.plain"
        Box(Modifier.fillMaxSize().background(tint.ground).clearAndSetSemantics { testTag = drawn }) {
            if (art.labels.isNotEmpty()) {
                Label(art.labels, tint.ink, width, height, band, maxLabelLines)
            } else art.initial?.let { letter ->
                Monogram(letter, tint.ink, minOf(width, height))
            }
        }
    }
}

// Fixed in dp, not sp: it is decoration, and its size follows the image, not the text size.
@Composable
private fun BoxScope.Monogram(letter: String, ink: Color, size: Dp) {
    val fontSize = with(LocalDensity.current) { (size * 0.56f).toSp() }
    Text(
        letter,
        color = ink,
        style = TextStyle(
            fontFamily = Fraunces,
            fontWeight = FontWeight.Normal,
            fontSize = fontSize,
            lineHeight = fontSize,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
        ),
        maxLines = 1,
        modifier = Modifier.align(Alignment.Center)
    )
}

@Composable
private fun BoxScope.Label(
    names: List<String>,
    ink: Color,
    width: Dp,
    height: Dp,
    band: Boolean,
    maxLines: Int
) {
    val density = LocalDensity.current
    // A square: 18% of its side, never under 10. A band: 21 sp, 25 for a banner as tall as the
    // reading view's.
    val fontSize = if (band) {
        (if (height >= 140.dp) 25 else 21).dp
    } else {
        maxOf(10.dp, minOf(width, height) * 0.18f)
    }.let { with(density) { it.toSp() } }
    val text: AnnotatedString = buildAnnotatedString {
        names.forEachIndexed { i, name ->
            if (i > 0) withStyle(SpanStyle(color = ink.copy(alpha = 0.5f), fontStyle = FontStyle.Normal)) { append("  ·  ") }
            append(name)
        }
    }
    Text(
        text,
        color = ink,
        style = TextStyle(
            fontFamily = Fraunces,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Normal,
            fontSize = fontSize,
            lineHeight = fontSize * 1.12f,
            textAlign = if (band) TextAlign.Start else TextAlign.Center
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .align(if (band) Alignment.BottomStart else Alignment.Center)
            .padding(if (band) PaddingValues(horizontal = 18.dp, vertical = 14.dp) else PaddingValues(4.dp))
    )
}

/**
 * A recipe's picture: [model] (a web image) when it has one, else, and when it fails to load,
 * the recipe's [RecipeImagePlaceholder] (#403). The photo's own semantics are kept as they were
 * ([contentDescription]); the placeholder adds none. With the flag off, a failed load is blank
 * as before.
 */
@Composable
fun RecipeImage(
    model: String?,
    recipe: NoPhotoSource,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    style: NoPhotoStyle = NoPhotoStyle.Auto,
    maxLabelLines: Int = 2,
    whenOff: @Composable (Modifier) -> Unit = { Box(it.background(MaterialTheme.colorScheme.outlineVariant)) }
) {
    if (model == null) {
        RecipeImagePlaceholder(recipe, modifier, style, maxLabelLines, whenOff)
        return
    }
    var failed by remember(model) { mutableStateOf(false) }
    Box(modifier) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize()
        )
        if (failed && noPhotoImageOn()) {
            RecipeImagePlaceholder(recipe, Modifier.fillMaxSize(), style, maxLabelLines, whenOff)
        }
    }
}
