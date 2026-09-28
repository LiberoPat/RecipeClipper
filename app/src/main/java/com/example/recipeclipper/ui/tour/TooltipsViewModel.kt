package com.example.recipeclipper.ui.tour

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.local.TourPreferences
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.data.model.TooltipScreen
import com.example.recipeclipper.data.model.TooltipVisit
import com.example.recipeclipper.data.model.Tooltips
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** The tooltip showing now (#190), and the screen appearance ([token]) it shows on. */
data class TooltipsUiState(val current: Tooltip? = null, val token: String? = null)

/**
 * The tooltips (#190), for the whole app: MainActivity holds one and provides it to every screen
 * through [LocalTooltips], so a screen only wraps itself in a [TooltipHost] and marks its
 * controls with [tooltipAnchor]. Which tooltip shows is [Tooltips]' rule; this keeps the visit,
 * what the screen reports is on it, and the seen state. iOS's `TooltipsViewModel`.
 */
@HiltViewModel
class TooltipsViewModel @Inject constructor(
    private val preferences: TourPreferences,
    flags: FeatureFlags
) : ViewModel() {

    private data class Report(val visible: Set<Tooltip>, val ready: Boolean)

    private val visit = MutableStateFlow<TooltipVisit?>(null)

    // Each screen's last report, by its token: a screen still composed beside another (in a
    // transition) may go on reporting, and must not overwrite the one now on screen.
    private val reports = MutableStateFlow<Map<String, Report>>(emptyMap())

    // Nothing shows until the stored state has been read, so a seen tooltip never flashes. The
    // tooltip picked is kept as the visit's, so another can't follow it in the same visit.
    val uiState: StateFlow<TooltipsUiState> =
        combine(preferences.seenTooltips, flags.values, visit, reports) { seen, values, visit, reports ->
            val report = visit?.let { reports[it.token] }
            val current = Tooltips.current(
                visit, seen, values::isOn,
                visible = report?.visible.orEmpty(),
                ready = report?.ready == true
            )
            TooltipsUiState(current, visit?.token)
        }.onEach { state ->
            val shown = state.current ?: return@onEach
            visit.update { it?.let { visit -> Tooltips.shown(visit, shown) } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TooltipsUiState())

    /** A screen appeared ([token] names this appearance; the same after a rotation). */
    fun onVisit(token: String, screen: TooltipScreen) = visit.update { Tooltips.visit(it, token, screen) }

    fun onLeave(token: String) {
        visit.update { Tooltips.leave(it, token) }
        reports.update { it - token }
    }

    /** What the screen [token] has on it now: the anchors in view, and whether it's settled and uncovered. */
    fun onReport(token: String, visible: Set<Tooltip>, ready: Boolean) {
        reports.update { it + (token to Report(visible, ready)) }
    }

    /** "Got it", or a tap on the bubble: seen for good, and nothing more this visit. */
    fun onDismiss(tooltip: Tooltip) {
        preferences.setTooltipSeen(tooltip, true)
        visit.update { it?.let(Tooltips::closed) }
    }

    /** Settings' "Show tips again": every tooltip shows once more, one at a time. */
    fun onReplay() = Tooltip.entries.forEach { preferences.setTooltipSeen(it, false) }
}
