package com.example.recipeclipper.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.recipeclipper.R
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Turns an [Elapsed] into words. Separate from [TimeAgo] because this needs resources and
 * that must stay pure. The date pattern is a resource too: not every language writes
 * "MMM d".
 */
@Composable
fun Elapsed.text(): String = when (this) {
    is Elapsed.JustNow -> stringResource(R.string.time_just_now)
    is Elapsed.Minutes -> pluralStringResource(R.plurals.time_minutes_ago, count, count)
    is Elapsed.Hours -> pluralStringResource(R.plurals.time_hours_ago, count, count)
    is Elapsed.Yesterday -> stringResource(R.string.time_yesterday)
    is Elapsed.Days -> pluralStringResource(R.plurals.time_days_ago, count, count)
    is Elapsed.OnDate ->
        SimpleDateFormat(stringResource(R.string.time_date_format), LocalLocale.current.platformLocale)
            .format(Date(millis))
}
