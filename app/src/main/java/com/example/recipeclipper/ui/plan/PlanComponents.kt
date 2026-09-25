package com.example.recipeclipper.ui.plan

import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.Servings
import java.util.Date
import java.util.Locale

// Day labels. A day is an epoch day, so it is formatted at midnight UTC with a UTC calendar:
// the local zone could put that instant on the day before. ICU's SimpleDateFormat, the pair of
// the ICU skeleton pattern: it may hold ICU-only letters ("ccc" for a standalone weekday), which
// Android's java.text copy also takes but the JVM's (so Robolectric's, #91) doesn't.

private fun format(day: Long, skeleton: String): String {
    val locale = Locale.getDefault()
    val formatter = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(PlanDays.utcMillis(day)))
}

/** "Monday 23", in the locale's order. */
internal fun dayTitle(day: Long): String = format(day, "EEEEd")

/** "Mon" */
internal fun shortWeekday(day: Long): String = format(day, "EEE")

/** "Sep 23", in the locale's order. */
internal fun shortDate(day: Long): String = format(day, "MMMd")

/** "23" */
internal fun dayOfMonth(day: Long): String = format(day, "d")

/** "Sep 21 – 27", or across months "Sep 28 – Oct 4". */
internal fun weekRange(start: Long): String {
    val end = start + 6
    val sameMonth = format(start, "M") == format(end, "M")
    return format(start, "MMMd") + " – " + (if (sameMonth) format(end, "d") else format(end, "MMMd"))
}

/**
 * The strip of days in a plan sheet: this week and next, each a short weekday over its date.
 * The chosen day is ringed in paprika, today's date is paprika text. An exclusive choice, so
 * each cell is `selectable` with a radio-button role.
 */
@Composable
internal fun DayStrip(days: List<Long>, today: Long, selected: Long, onSelect: (Long) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        days.forEach { day ->
            val isSelected = day == selected
            val shape = RoundedCornerShape(12.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .border(
                        BorderStroke(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        shape
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(day) })
                    .padding(vertical = 8.dp, horizontal = 6.dp)
                    .testTag("planDay-$day")
            ) {
                Text(
                    shortWeekday(day),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    dayOfMonth(day),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (day == today) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** The meal types as a row of outlined choices, the chosen one ringed in paprika. */
@Composable
internal fun MealTypeChoices(types: List<MealType>, selected: Long?, onSelect: (Long) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        types.forEach { type ->
            val isSelected = type.id == selected
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Transparent,
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(type.id) })
            ) {
                Text(
                    type.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** "Serves − 4 +", as on the recipe screen. */
@Composable
internal fun ServingsPicker(servings: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.label_serves), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(6.dp))
        val decrease = stringResource(R.string.cd_decrease_servings)
        FilledTonalIconButton(
            onClick = { onChange(servings - 1) },
            enabled = servings > 1,
            modifier = Modifier.semantics { contentDescription = decrease }
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            "$servings",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 32.dp)
        )
        val increase = stringResource(R.string.cd_increase_servings)
        FilledTonalIconButton(
            onClick = { onChange(servings + 1) },
            enabled = servings < Servings.MAX,
            modifier = Modifier.semantics { contentDescription = increase }
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/** Remembers [dayTitle] per day, so a list of days isn't reformatting on every frame. */
@Composable
internal fun rememberDayTitle(day: Long): String = remember(day) { dayTitle(day) }
