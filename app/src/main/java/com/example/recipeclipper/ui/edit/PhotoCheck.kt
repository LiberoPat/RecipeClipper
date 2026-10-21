package com.example.recipeclipper.ui.edit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import coil.transform.Transformation
import com.example.recipeclipper.R
import com.example.recipeclipper.data.PhotoPost
import com.example.recipeclipper.data.remote.LineFlag
import com.example.recipeclipper.data.remote.PhotoBox

/**
 * The pictures read, as a row of thumbnails over the cards (#286), with "2 pages · 5 lines to
 * check". Tapping one shows it large under the row, and tapping it again hides it, to check the
 * lines that aren't listed against it. A scan's pages are read where they are, never cached.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageThumbnails(post: PhotoPost, state: EditRecipeUiState, onPage: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        post.imageUrls.forEachIndexed { index, image ->
            val open = state.openPage == index
            val shape = RoundedCornerShape(8.dp)
            PageImage(
                image, state.scan, pageDescription(post, state, index), ContentScale.Crop,
                Modifier
                    .size(56.dp, 72.dp)
                    .clip(shape)
                    .then(if (open) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .semantics { selected = open }
                    .clickable(role = Role.Button) { onPage(index) }
                    .testTag("edit.page.$index")
            )
        }
        val pages = pluralStringResource(
            if (state.scan) R.plurals.photo_pages_count else R.plurals.photo_photos_count, state.pageCount, state.pageCount
        )
        val lines = state.checks.size
        Text(
            if (lines == 0) pages else "$pages · " + pluralStringResource(R.plurals.photo_lines_to_check, lines, lines),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically).testTag("edit.pageCount")
        )
    }
    state.openPage?.let { page -> post.imageUrls.getOrNull(page) }?.let { image ->
        PageImage(
            image, state.scan, pageDescription(post, state, state.openPage), ContentScale.FillWidth,
            Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(bottom = 12.dp)
                .clip(RoundedCornerShape(12.dp)).testTag("edit.openPage")
        )
    }
}

@Composable
private fun pageDescription(post: PhotoPost, state: EditRecipeUiState, index: Int): String =
    if (state.scan) stringResource(R.string.scan_page_description, index + 1, post.imageUrls.size)
    else stringResource(R.string.photo_image_description, post.title)

/** A picture read: a post's, from the image cache, or a scan's page, read where it is and never
 *  cached (#226: a reused camera file must not show an earlier picture). */
@Composable
private fun PageImage(image: String, local: Boolean, description: String, scale: ContentScale, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = if (local) {
            remember(image) {
                ImageRequest.Builder(context).data(image)
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
            }
        } else image,
        contentDescription = description,
        contentScale = scale,
        modifier = modifier
    )
}

/**
 * The strip of the photo a line was read from (#286): its picture decoded at most
 * [STRIP_PAGE_SIDE] pixels a side and cropped to the line's box ([CropToBox]), so only the strip
 * is kept, never a whole page. A post's picture comes from the image cache the read filled (and
 * is fetched again only if the cache has let it go); a scan's page is read where it is, never
 * cached. Shown as tall as the card's width allows, between [STRIP_MIN] and [STRIP_MAX]; past
 * that a long line's end is cut, never its start, where the amount is. A hairline band holds its
 * place until it loads.
 */
@Composable
internal fun Strip(strip: PhotoStrip, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(strip) {
        ImageRequest.Builder(context).data(strip.image)
            .size(STRIP_PAGE_SIDE).precision(Precision.INEXACT)
            .transformations(CropToBox(strip.box))
            .apply {
                if (strip.local) {
                    memoryCachePolicy(CachePolicy.DISABLED)
                    diskCachePolicy(CachePolicy.DISABLED)
                }
            }
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    val loaded = painter.state is AsyncImagePainter.State.Success
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val size = painter.intrinsicSize
        val height = if (loaded && size.isSpecified && size.width > 0f) stripHeight(maxWidth, size.width / size.height) else STRIP_MIN
        Image(
            painter,
            contentDescription = stringResource(R.string.photo_strip_description),
            contentScale = ContentScale.FillHeight,
            alignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .then(if (loaded) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        )
    }
}

/** A strip's height on a card [width] wide, for a strip [aspect] times as wide as it is tall. */
internal fun stripHeight(width: Dp, aspect: Float): Dp = (width / aspect).coerceIn(STRIP_MIN, STRIP_MAX)

internal val STRIP_MIN = 28.dp
internal val STRIP_MAX = 56.dp
private const val STRIP_PAGE_SIDE = 1600

/** Crops a decoded picture to a line's strip (#286): its box with a little room ([PhotoBox.padded]). */
internal class CropToBox(private val box: PhotoBox) : Transformation {
    override val cacheKey: String = "photoStrip(${box.left},${box.top},${box.right},${box.bottom})"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val (left, top, right, bottom) = stripPixels(box, input.width, input.height)
        return Bitmap.createBitmap(input, left, top, right - left, bottom - top)
    }
}

/** [box]'s strip ([PhotoBox.padded]) in whole pixels of a [width] by [height] picture: left, top,
 *  right, bottom, at least one pixel each way and inside the picture. */
internal fun stripPixels(box: PhotoBox, width: Int, height: Int): List<Int> {
    val strip = box.padded(width, height)
    val left = (strip.left * width).toInt().coerceIn(0, width - 1)
    val top = (strip.top * height).toInt().coerceIn(0, height - 1)
    val right = kotlin.math.ceil(strip.right * width).toInt().coerceIn(left + 1, width)
    val bottom = kotlin.math.ceil(strip.bottom * height).toInt().coerceIn(top + 1, height)
    return listOf(left, top, right, bottom)
}

/**
 * One line to check (#286), as a card: the strip of the photo it was read from (none when the
 * reader didn't say where), the line as a field, which edits it in its box too, then why it is
 * listed: a note for an amount that only looks large (#281), or the fraction chips (#245).
 */
@Composable
internal fun CheckCard(
    index: Int,
    check: CheckLine,
    fractions: List<String>,
    onLine: (Int, String) -> Unit,
    onFraction: (LineFlag, String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp)
            .testTag("edit.check.$index")
    ) {
        check.strip?.let {
            Strip(it, Modifier.testTag("edit.strip.$index"))
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = check.flag.raw,
            onValueChange = { onLine(index, it) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.background,
                unfocusedContainerColor = MaterialTheme.colorScheme.background
            ),
            modifier = Modifier.fillMaxWidth().testTag("edit.checkLine.$index")
        )
        if (check.flag.largeOnly) LineNote(stringResource(R.string.photo_check_large))
        if (check.flag.chips) FractionChips(index, check.flag, fractions, onFraction)
    }
}

/**
 * A line whose amount holds a fraction glyph's look-alike (#245), or glyphs run together (#276,
 * "½⅓"): the fractions as one-tap replacements, since "%" can't say whether it was ½, ¼ or ¾.
 * The cook can still type instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FractionChips(index: Int, flag: LineFlag, fractions: List<String>, onFraction: (LineFlag, String) -> Unit) {
    LineNote(stringResource(R.string.photo_fraction_prompt))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        fractions.forEach { fraction ->
            val spoken = stringResource(R.string.photo_fraction_chip, fraction, flag.shown)
            OutlinedButton(
                onClick = { onFraction(flag, fraction) },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .semantics { contentDescription = spoken }
                    .testTag("edit.fraction.$index.$fraction")
            ) {
                Text(fraction, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** A muted line under a line to check: why it's listed (#281), or what its chips are for (#245). */
@Composable
private fun LineNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}
