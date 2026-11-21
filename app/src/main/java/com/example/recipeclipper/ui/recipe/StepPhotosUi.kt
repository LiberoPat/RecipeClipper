package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.StepPhoto
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.theme.Fraunces
import com.example.recipeclipper.ui.theme.Ground
import com.example.recipeclipper.ui.theme.Ink
import com.example.recipeclipper.ui.theme.PaprikaTextOnInk

/** True with the `stepPhotos` flag on (#433); off, every step is drawn as it was. */
@Composable
internal fun stepPhotosOn(): Boolean = LocalFlagValues.current.isOn(Flag.STEP_PHOTOS)

/** The reading view's thumbnail, and the one beside the current step's timer in cook mode. */
internal val StepThumbnailSize = 54.dp

/** Cook mode's done and upcoming rows: smaller, no badge. */
internal val StepThumbnailSmall = 40.dp

// The viewer's ground and words, the same in light and dark: a photo reads best on near-black.
private val ViewerGround = Color(0xFF0E0C0B)
private val ViewerInk = Color(0xFFF1ECE6)

/**
 * A step's photos as one thumbnail (#433, option B): the first photo, square and rounded, with a
 * small badge carrying its number (the number its "(n)" marker in the text uses). Two or more
 * stack: the second shows behind it, offset, with a "+1" pill. A tap opens the viewer on the
 * first. Nothing at all when the first photo fails to load, so the step's text takes the room.
 */
@Composable
internal fun StepPhotoThumbnail(
    photos: List<StepPhoto>,
    onOpen: (position: Int) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = StepThumbnailSize
) {
    val front = photos.firstOrNull() ?: return
    var failed by remember(front.url) { mutableStateOf(false) }
    if (failed) return
    val step = front.step + 1
    val label = if (photos.size == 1) {
        stringResource(R.string.step_photo_open, front.number, step)
    } else {
        pluralStringResource(R.plurals.step_photos_open, photos.size, photos.size, step)
    }
    val action = stringResource(R.string.step_photo_open_action)
    val corner = RoundedCornerShape(9.dp)
    Box(
        modifier
            .size(size)
            .testTag("stepPhoto.thumbnail.${front.step}")
            .clickable(onClickLabel = action, role = Role.Button) { onOpen(front.position) }
            // One button for TalkBack: "Photo 3 of step 2", or "2 photos of step 3"; the badge and pill are drawn, not read.
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label = action) { onOpen(front.position); true }
            }
    ) {
        photos.getOrNull(1)?.let { back ->
            var backFailed by remember(back.url) { mutableStateOf(false) }
            if (!backFailed) {
                AsyncImage(
                    model = back.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { backFailed = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(4.dp, (-4).dp)
                        .alpha(0.55f)
                        .clip(corner)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        AsyncImage(
            model = front.url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier
                .fillMaxSize()
                .clip(corner)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        NumberBadge(front.number, Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 5.dp))
        if (photos.size > 1) {
            Text(
                "+${photos.size - 1}",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 13.sp),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(4.dp, (-8).dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * Cook mode's done or upcoming row (#433): the step's first photo, small and with no badge. A
 * picture of the step, nothing to announce: the row's own tap (make it current) is the action.
 */
@Composable
internal fun StepPhotoMini(photos: List<StepPhoto>, modifier: Modifier = Modifier) {
    val front = photos.firstOrNull() ?: return
    var failed by remember(front.url) { mutableStateOf(false) }
    if (failed) return
    AsyncImage(
        model = front.url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = { failed = true },
        modifier = modifier
            .size(StepThumbnailSmall)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.outlineVariant)
            .testTag("stepPhoto.mini.${front.step}")
            .clearAndSetSemantics {}
    )
}

/** The photo's number, as the step's "(n)" marker writes it: white on ink, bottom left. */
@Composable
private fun NumberBadge(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Ink.copy(alpha = 0.78f))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$number",
            style = TextStyle(
                fontWeight = FontWeight.Bold, fontSize = 10.5.sp, lineHeight = 12.sp, fontFeatureSettings = "tnum"
            ),
            color = Ground
        )
    }
}

/**
 * Every step photo of the recipe, full screen on a dark ground (#433): Close, "Photo 3 of 7" and
 * "Step 3" over the photo, the page dots and the step's text under it. Swiping pages through the
 * photos in step order; the page shown is the ViewModel's ([RecipeUiState.stepPhotoViewer]), so a
 * rotation keeps it. A photo that fails to load leaves the dark ground, with its caption.
 */
@Composable
internal fun StepPhotoViewer(content: RecipeContent.Success, position: Int, actions: RecipeActions) {
    val photos = content.allStepPhotos
    if (photos.isEmpty()) return
    val start = position.coerceIn(0, photos.lastIndex)
    val pager = rememberPagerState(initialPage = start) { photos.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { actions.onStepPhotoPage(it) }
    }
    val shown = photos[pager.currentPage.coerceIn(0, photos.lastIndex)]
    Dialog(
        onDismissRequest = actions.onStepPhotoClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(ViewerGround).testTag("stepPhoto.viewer")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(end = 16.dp)
                ) {
                    IconButton(onClick = actions.onStepPhotoClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_photo), tint = ViewerInk)
                    }
                    Text(
                        stringResource(R.string.step_photo_position, shown.position + 1, photos.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = ViewerInk.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        stringResource(R.string.step_photo_step, shown.step + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = ViewerInk.copy(alpha = 0.75f)
                    )
                }
                HorizontalPager(
                    state = pager,
                    key = { photos[it].position },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) { page ->
                    val photo = photos[page]
                    AsyncImage(
                        model = photo.url,
                        contentDescription = stringResource(R.string.step_photo_open, photo.number, photo.step + 1),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().testTag("stepPhoto.page.${photo.position}")
                    )
                }
                PageDots(photos.size, pager.currentPage)
                StepCaption(shown.step, content.instructions.getOrNull(shown.step).orEmpty())
            }
        }
    }
}

/** One dot per photo, the shown one bright. Decorative: "Photo 3 of 7" says the same. */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp).clearAndSetSemantics {}
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(ViewerInk.copy(alpha = if (i == current) 1f else 0.3f))
            )
        }
    }
}

/** The step the photo shows: its number in Fraunces paprika, then its text as the reading view shows it. */
@Composable
private fun StepCaption(step: Int, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, bottom = 16.dp)
            .testTag("stepPhoto.caption")
    ) {
        Text(
            "${step + 1}",
            style = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
            color = PaprikaTextOnInk,
            modifier = Modifier.width(28.dp)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ViewerInk.copy(alpha = 0.9f)
        )
    }
}
