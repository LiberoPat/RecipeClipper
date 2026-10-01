package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.PhotoPost
import com.example.recipeclipper.data.model.ParseError

/**
 * A load that failed: what went wrong, and what can be done about it. Every error offers
 * "Try again"; a page with no recipe also offers "Clip it yourself" (#37) and "Report this
 * site" (#30), and a Reddit post with a photo "Read the photo" (#198).
 */
@Composable
internal fun RecipeErrorView(
    error: ParseError,
    state: RecipeUiState,
    actions: RecipeActions,
    photoTextEnabled: Boolean,
    onClip: (url: String) -> Unit,
    onReadPhoto: (PhotoPost) -> Unit
) {
    StatusView(actions.onBack) {
        (error as? ParseError.NoTranscription)?.let { PostPreview(it) }
        Text(
            error.toMessage(),
            style = MaterialTheme.typography.bodyLarge,
            // No transcription (#11) is an outcome, not a failure: muted, not red.
            color = if (error is ParseError.NoTranscription) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        // Every error offers "Try again", no-recipe included: a café or hotel
        // captive portal serves its login page, which parses as a page with no
        // recipe, and the same link works once you're through it.
        Spacer(Modifier.height(16.dp))
        Column {
            val clipUrl = state.clipUrl
            val photoPost = state.photoPost?.takeIf { photoTextEnabled }
            if (photoPost != null) {
                // A post with a photo (#198): Try again, and beside it the
                // photo read on the device, for the cook to check.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = actions.onRetry, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_try_again))
                    }
                    Button(onClick = { onReadPhoto(photoPost) }, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_read_photo))
                    }
                }
            } else if (clipUrl == null) {
                Button(onClick = actions.onRetry, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.action_try_again))
                }
            } else {
                // A page with no recipe data (#37): Try again stays first,
                // outlined; clipping it by hand is the one filled button, and
                // reporting the site (#30) is the quiet option under it.
                OutlinedButton(onClick = actions.onRetry, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.action_try_again))
                }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.clip_offer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onClip(clipUrl) }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.action_clip_it_yourself))
                }
            }
            // Only for a page with no recipe (the ViewModel decides): the one
            // error that means "unsupported" rather than "try again".
            if (state.reportSiteUrl != null) {
                TextButton(onClick = actions.onReportSite) {
                    Text(
                        stringResource(R.string.action_report_site),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Resolves a [ParseError] cause to the copy shown for it. The data layer only hands over
 *  the cause; choosing the sentence is the UI's job. */
@Composable
internal fun ParseError.toMessage(): String = when (this) {
    ParseError.NoRecipeFound -> stringResource(R.string.error_no_recipe_found)
    is ParseError.NoTranscription -> stringResource(R.string.error_no_transcription)
    is ParseError.Blocked -> stringResource(R.string.error_blocked, httpStatus)
    ParseError.Offline -> stringResource(R.string.error_offline)
    is ParseError.FetchFailed -> stringResource(
        R.string.error_fetch_failed,
        detail ?: stringResource(R.string.error_fetch_failed_unknown_detail)
    )
    ParseError.SaveFailed -> stringResource(R.string.error_save_failed)
    ParseError.NotSaved -> stringResource(R.string.error_not_saved)
    ParseError.NothingToShow -> stringResource(R.string.error_nothing_to_show)
    ParseError.HumanCheck -> stringResource(R.string.error_human_check)
}

/**
 * A Reddit post with no recipe as text: its title and photo above the note, so the user sees
 * what they shared (the recipe may well be legible in the photo itself). Not the ReadingView:
 * there is nothing to scale, tick or cook.
 */
@Composable
private fun PostPreview(post: ParseError.NoTranscription) {
    post.imageUrl?.let { image ->
        AsyncImage(
            model = image,
            contentDescription = post.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        Spacer(Modifier.height(16.dp))
    }
    Text(post.title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
}

/** Loading and errors: a back button and one message, nothing to read yet. */
@Composable
internal fun StatusView(onBack: () -> Unit, body: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 4.dp)) {
        BackButton(onBack)
        Spacer(Modifier.height(24.dp))
        body()
    }
}
