package com.example.recipeclipper.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.recipeclipper.R

/**
 * Today's meal (#402), in the Continue cooking card's place while nothing is being cooked: the
 * lead meal's photo (a plain band without one) and name under "Today", then "Start cooking"
 * into cook mode at the planned servings. A planned note shows its text and no button. The
 * other meals still to cook today follow, one line each, and open their recipe. The photo and
 * name open the recipe without cooking it.
 */
@Composable
internal fun TodayCard(
    card: TodayCard,
    onStartCooking: (TodayMeal) -> Unit,
    onOpen: (TodayMeal) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val lead = card.lead
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth().testTag("todayCard")
            .clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().then(
                if (lead.recipeId != null) Modifier.clickable(role = Role.Button, onClick = { onOpen(lead) }) else Modifier
            )
        ) {
            if (lead.imageUrl != null) {
                AsyncImage(
                    model = lead.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(136.dp)
                )
            } else {
                Box(Modifier.fillMaxWidth().height(56.dp).background(colors.tertiary.copy(alpha = 0.12f)))
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Text(
                    stringResource(R.string.home_today).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    lead.title,
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
            if (lead.recipeId != null) {
                Button(
                    onClick = { onStartCooking(lead) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                ) {
                    Text(stringResource(R.string.action_start_cooking))
                }
            }
            card.others.forEach { meal ->
                Text(
                    stringResource(R.string.home_today_other, meal.mealType, meal.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).then(
                        if (meal.recipeId != null) Modifier.clickable(role = Role.Button, onClick = { onOpen(meal) }) else Modifier
                    )
                )
            }
        }
    }
}
