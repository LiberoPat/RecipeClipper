package com.example.recipeclipper.ui.mealplan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.CutOffMessage
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.toMessage
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * An AI's answer shared in that stopped before its recipe ended (#352): [error]
 * ([ParseError.CutOff]) in the screen's words, and "Copy a message for the AI" to ask the chat to
 * send it again. Nothing is saved or kept; Back returns to where the share came from.
 */
@Composable
fun CutOffScreen(onBack: () -> Unit, error: ParseError = ParseError.CutOff) {
    val context = LocalContext.current
    val words = cutOffWords()
    val copyLabel = stringResource(R.string.cutoff_copy_action)
    var copied by rememberSaveable { mutableStateOf(false) }
    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) { BackButton(onBack) }
                Spacer(Modifier.height(24.dp))
                Text(
                    error.toMessage(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("cutoff_message")
                )
                Spacer(Modifier.height(16.dp))
                CutOffCopy(copied) {
                    copyToClipboard(context, copyLabel,CutOffMessage.forRecipe(words))
                    copied = true
                }
            }
        }
    }
}

/** "Copy a message for the AI", or once copied the muted confirmation in its place (no snackbar: those are for undo). */
@Composable
internal fun CutOffCopy(copied: Boolean, onCopy: () -> Unit) {
    if (copied) {
        Text(
            stringResource(R.string.cutoff_copied),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("cutoff_copied")
        )
    } else {
        Button(onClick = onCopy, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("cutoff_copy")) {
            Text(stringResource(R.string.cutoff_copy_action))
        }
    }
}

private const val DAYS_MARKER = "{days}"

/** The follow-up message's sentences in the UI language, for [CutOffMessage]. */
@Composable
internal fun cutOffWords(): CutOffMessage.Words {
    // The sentence read with a marker where the days go (never `LocalContext.current.getString`, which
    // isn't configuration-aware), the days put in when the message is built.
    val withMarker = stringResource(R.string.cutoff_ask_plan, DAYS_MARKER)
    return CutOffMessage.Words(
        plan = { days -> withMarker.replace(DAYS_MARKER, days) },
        planRest = stringResource(R.string.cutoff_ask_plan_rest),
        recipe = stringResource(R.string.cutoff_ask_recipe)
    )
}

/** A platform effect, so it lives in the view layer: [text] to the clipboard. */
internal fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}
