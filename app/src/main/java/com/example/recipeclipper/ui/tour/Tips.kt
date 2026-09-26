package com.example.recipeclipper.ui.tour

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.local.TourPreferences
import com.example.recipeclipper.data.model.Tip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The tips still to show (#151): not yet dismissed, and with their flag on. */
data class TipsUiState(val shown: Set<Tip> = emptySet())

/**
 * The one-time tips (#151), for the whole app: MainActivity holds one and provides it to every
 * screen through [LocalTips], so a screen only names its [Tip] and needs no ViewModel of its
 * own. A tip for a tab behind the `mealPlan` flag hides while the flag is off.
 */
@HiltViewModel
class TipsViewModel @Inject constructor(
    private val preferences: TourPreferences,
    flags: FeatureFlags
) : ViewModel() {

    // Nothing shows until the stored state has been read, so a dismissed tip never flashes.
    val uiState: StateFlow<TipsUiState> = combine(preferences.seenTips, flags.values) { seen, values ->
        TipsUiState(
            Tip.entries.filterTo(mutableSetOf()) { it !in seen && (!it.mealPlan || values.isOn(Flag.MEAL_PLAN)) }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TipsUiState())

    fun onDismiss(tip: Tip) = preferences.setTipSeen(tip, true)
}

/** What [TipCallout] reads: the tips to show and how to dismiss one. None by default (tests). */
class TipsHost(val shown: Set<Tip>, val onDismiss: (Tip) -> Unit) {
    companion object {
        val NONE = TipsHost(emptySet()) {}
    }
}

val LocalTips = compositionLocalOf { TipsHost.NONE }

@StringRes
private fun Tip.text(): Int = when (this) {
    Tip.RECIPE -> R.string.tip_recipe
    Tip.COOK_MODE -> R.string.tip_cook_mode
    Tip.WEEK -> R.string.tip_week
    Tip.GROCERIES -> R.string.tip_groceries
    Tip.PANTRY -> R.string.tip_pantry
}

/**
 * [tip]'s one small callout, in place, until tapped: nothing if it has been dismissed. It sits
 * in the screen's flow (never over it), so it never blocks what is under it. The whole card is
 * the dismiss button, labelled for TalkBack; the × only shows that it closes.
 */
@Composable
fun TipCallout(tip: Tip, modifier: Modifier = Modifier) {
    val tips = LocalTips.current
    if (tip !in tips.shown) return
    val dismiss = stringResource(R.string.cd_dismiss_tip)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .testTag("tip-${tip.name}")
                .clickable(onClickLabel = dismiss, role = Role.Button) { tips.onDismiss(tip) }
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Text(
                stringResource(tip.text()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
