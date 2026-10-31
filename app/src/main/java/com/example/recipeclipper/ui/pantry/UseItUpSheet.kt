package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ExpiryReminders
import com.example.recipeclipper.data.model.UseItUp
import com.example.recipeclipper.data.model.UseItUpGroup
import com.example.recipeclipper.ui.recipe.Hairline

/**
 * The "Use it up" sheet (#305): for each pantry item expiring soon, the saved recipes that use
 * it, each with one tap to plan it today or on the item's use-by day (tomorrow, the reminder's
 * window). A recipe already planned that day says so instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UseItUpSheet(
    groups: List<UseItUpGroup>,
    today: Long,
    planned: Set<Pair<Long, Long>>,
    onPlan: (recipeId: Long, day: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val locale = LocalConfiguration.current.locales[0]
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding().testTag("useItUpSheet"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            item(key = "title") {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(stringResource(R.string.use_it_up_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.use_it_up_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            for (group in groups) {
                val item = group.item
                item(key = "item-${item.id}") {
                    val sentence = if (item.expiresDay == today) R.string.expiry_one_today else R.string.expiry_one_tomorrow
                    Text(
                        ExpiryReminders.capitalized(stringResource(sentence, item.name.trim()), locale),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }
                items(group.recipes, key = { "recipe-${item.id}-${it.id}" }) { recipe ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("useItUp-${item.id}-${recipe.id}")) {
                        Text(recipe.title, style = MaterialTheme.typography.bodyLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            for (day in UseItUp.planDays(item, today)) {
                                PlanButton(day == today, (recipe.id to day) in planned) { onPlan(recipe.id, day) }
                            }
                        }
                    }
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun PlanButton(isToday: Boolean, planned: Boolean, onClick: () -> Unit) {
    val label = when {
        planned && isToday -> R.string.use_it_up_planned_today
        planned -> R.string.use_it_up_planned_tomorrow
        isToday -> R.string.use_it_up_plan_today
        else -> R.string.use_it_up_plan_tomorrow
    }
    OutlinedButton(onClick = onClick, enabled = !planned) { Text(stringResource(label)) }
}
