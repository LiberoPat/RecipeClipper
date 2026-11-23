package com.example.recipeclipper.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.StepTimers
import com.example.recipeclipper.ui.common.NoPhotoStyle
import com.example.recipeclipper.ui.common.RecipeImage
import com.example.recipeclipper.ui.common.noPhotoImageOn
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import com.example.recipeclipper.ui.theme.Corners

/**
 * Continue cooking (#286): the most recent recipe's photo, or a plain themed band when it has
 * none, its name, and only what is true of it now: the step stopped on and a running timer while
 * it is being cooked ([resume]), then "Resume cooking" into cook mode, or else "Open recipe".
 * The photo and name open the recipe, as the Continue cooking row did.
 */
@Composable
internal fun ResumeCard(recipe: RecipeSummary, resume: CookResume?, onResume: () -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth().clip(Corners.Card).background(colors.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)) {
            if (noPhotoImageOn()) {
                // A photo at 136 dp; with none (or one that fails to load) the recipe's designed
                // image, its first ingredient names set low on its tint, at 112 dp (#403).
                RecipeImage(
                    model = recipe.imageUrl,
                    recipe = recipe.noPhoto,
                    contentDescription = null,
                    style = NoPhotoStyle.Label,
                    modifier = Modifier.fillMaxWidth().height(if (recipe.imageUrl != null) 136.dp else 112.dp)
                )
            } else if (recipe.imageUrl != null) {
                AsyncImage(
                    model = recipe.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(136.dp)
                )
            } else {
                Box(Modifier.fillMaxWidth().height(56.dp).background(colors.secondaryContainer))
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Text(
                    stringResource(R.string.section_continue_cooking).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    recipe.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (resume != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    resume.timerSecondsLeft?.let { TimerTag(it) }
                    Text(
                        stringResource(R.string.cook_position, resume.step, resume.stepCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = if (resume != null) onResume else onOpen,
                shape = Corners.Pill,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            ) {
                Text(stringResource(if (resume != null) R.string.home_resume_cooking else R.string.home_open_recipe))
            }
        }
    }
}

/** A running timer's time left, "18:42 left", on the accent tone. */
@Composable
internal fun TimerTag(secondsLeft: Int) {
    val accent = MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clip(Corners.Pill).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Icon(painterResource(R.drawable.ic_timer), contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        Text(
            stringResource(R.string.home_timer_left, StepTimers.clock(secondsLeft)),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = accent
        )
    }
}
