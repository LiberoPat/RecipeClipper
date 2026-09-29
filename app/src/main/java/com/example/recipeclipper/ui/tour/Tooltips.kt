package com.example.recipeclipper.ui.tour

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.data.model.TooltipScreen
import com.example.recipeclipper.data.model.Tooltips
import com.example.recipeclipper.ui.common.LocalFlagValues
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The app's tooltips; none by default, so a screen test sees none unless it provides them. */
val LocalTooltips = staticCompositionLocalOf<TooltipsViewModel?> { null }

/** Which way the bubble goes from its control. AUTO: below if it fits, else above. */
enum class TooltipSide { AUTO, ABOVE, BELOW }

/**
 * A screen that shows tooltips (#190). A visit starts when it appears and ends when it's left
 * (not on a rotation). It reports which of its anchors are fully on screen, and whether it's
 * ready: past its first second, its window focused (no dialog, sheet, menu or share sheet over
 * it), no keyboard up, and not [blocked] (the screen's own reason, such as a snackbar).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TooltipHost(screen: TooltipScreen, blocked: Boolean = false, content: @Composable () -> Unit) {
    val tooltips = LocalTooltips.current
    val anchors = remember { TooltipAnchors() }
    var viewport by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInWindow() }) {
        CompositionLocalProvider(LocalTooltipAnchors provides anchors.takeIf { tooltips != null }) { content() }
        if (tooltips == null) return@Box

        val token = rememberSaveable(screen) { UUID.randomUUID().toString() }
        val activity = LocalActivity.current
        DisposableEffect(token) {
            tooltips.onVisit(token, screen)
            onDispose { if (activity?.isChangingConfigurations != true) tooltips.onLeave(token) }
        }
        var settled by remember(token) { mutableStateOf(false) }
        LaunchedEffect(token) {
            delay(Tooltips.SETTLE_MILLIS)
            settled = true
        }
        val ready = settled && LocalWindowInfo.current.isWindowFocused && !WindowInsets.isImeVisible && !blocked
        val visible by remember { derivedStateOf { anchors.visibleIn(viewport) } }
        LaunchedEffect(token, visible, ready) { tooltips.onReport(token, visible, ready) }

        val state by tooltips.uiState.collectAsStateWithLifecycle()
        val current = state.current?.takeIf { state.token == token } ?: return@Box
        val bounds = anchors[current] ?: return@Box
        TooltipBubble(
            text = stringResource(current.text(LocalFlagValues.current.isOn(Flag.MEAL_PLAN))),
            tag = "tooltip-${current.id}",
            anchor = bounds.full,
            side = bounds.side,
            onDismiss = { tooltips.onDismiss(current) }
        )
    }
}

/** Marks the control [tooltip] points at, inside a [TooltipHost]; nothing outside one. */
fun Modifier.tooltipAnchor(tooltip: Tooltip, side: TooltipSide = TooltipSide.AUTO): Modifier =
    this then TooltipAnchorElement(tooltip, side)

@StringRes
private fun Tooltip.text(mealPlan: Boolean): Int = when (this) {
    Tooltip.HOME_LINK -> R.string.tooltip_home_link
    Tooltip.HOME_NEW_RECIPE -> R.string.tooltip_home_new_recipe
    Tooltip.RECIPE_UNITS -> R.string.tooltip_recipe_units
    Tooltip.RECIPE_BOOKMARK -> R.string.tooltip_recipe_bookmark
    Tooltip.RECIPE_SHARE -> R.string.tooltip_recipe_share
    // Add to plan and Add to groceries are in the menu only with the meal plan on.
    Tooltip.RECIPE_MENU -> if (mealPlan) R.string.tooltip_recipe_menu else R.string.tooltip_recipe_menu_no_plan
    Tooltip.RECIPE_START_COOKING -> R.string.tooltip_recipe_start_cooking
    Tooltip.RECIPE_MADE_THIS -> R.string.tooltip_recipe_made_this
    Tooltip.COOK_DONE_NEXT -> R.string.tooltip_cook_done_next
    Tooltip.COOK_TAP_STEP -> R.string.tooltip_cook_tap_step
    Tooltip.COOK_TIMER -> R.string.tooltip_cook_timer
    Tooltip.COOK_INGREDIENTS -> R.string.tooltip_cook_ingredients
    Tooltip.WEEK_ADD -> R.string.tooltip_week_add
    Tooltip.WEEK_MONTH -> R.string.tooltip_week_month
    Tooltip.WEEK_MENU -> R.string.tooltip_week_menu
    Tooltip.GROCERIES_ADD -> R.string.tooltip_groceries_add
    Tooltip.GROCERIES_TICK -> R.string.tooltip_groceries_tick
    Tooltip.GROCERIES_LONG_PRESS -> R.string.tooltip_groceries_long_press
    Tooltip.GROCERIES_DONE_SHOPPING -> R.string.tooltip_groceries_done_shopping
    Tooltip.GROCERIES_MENU -> R.string.tooltip_groceries_menu
    Tooltip.PANTRY_ADD -> R.string.tooltip_pantry_add
    Tooltip.PANTRY_IN_STOCK -> R.string.tooltip_pantry_in_stock
    Tooltip.PANTRY_MENU -> R.string.tooltip_pantry_menu
    Tooltip.SETTINGS_UNITS -> R.string.tooltip_settings_units
    Tooltip.SETTINGS_SHOW_TIPS -> R.string.tooltip_settings_show_tips
}

/** Where a control is, in window coordinates: all of it, and the part not clipped away. */
internal data class AnchorBounds(val full: Rect, val shown: Rect, val side: TooltipSide, val owner: Any) {
    /** Wholly on screen: not scrolled partly away, and inside the host (so not under a bar). */
    fun isVisibleIn(viewport: Rect): Boolean =
        full.width > 0f && full.height > 0f && shown.covers(full) && viewport.covers(full)

    private fun Rect.covers(other: Rect) =
        left <= other.left + SLACK && top <= other.top + SLACK && right >= other.right - SLACK && bottom >= other.bottom - SLACK

    private companion object {
        const val SLACK = 1f
    }
}

/** The anchors inside one [TooltipHost], as they are laid out. */
internal class TooltipAnchors {
    private val bounds = mutableStateMapOf<Tooltip, AnchorBounds>()

    operator fun get(tooltip: Tooltip): AnchorBounds? = bounds[tooltip]

    fun put(tooltip: Tooltip, anchor: AnchorBounds) {
        if (bounds[tooltip] != anchor) bounds[tooltip] = anchor
    }

    fun remove(tooltip: Tooltip, owner: Any) {
        if (bounds[tooltip]?.owner === owner) bounds.remove(tooltip)
    }

    fun visibleIn(viewport: Rect?): Set<Tooltip> =
        if (viewport == null) emptySet() else bounds.filterValues { it.isVisibleIn(viewport) }.keys.toSet()
}

private val LocalTooltipAnchors = staticCompositionLocalOf<TooltipAnchors?> { null }

private data class TooltipAnchorElement(val tooltip: Tooltip, val side: TooltipSide) :
    ModifierNodeElement<TooltipAnchorNode>() {
    override fun create() = TooltipAnchorNode(tooltip, side)

    override fun update(node: TooltipAnchorNode) {
        if (node.tooltip != tooltip) node.anchors?.remove(node.tooltip, node)
        node.tooltip = tooltip
        node.side = side
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tooltipAnchor"
        properties["tooltip"] = tooltip
        properties["side"] = side
    }
}

private class TooltipAnchorNode(var tooltip: Tooltip, var side: TooltipSide) :
    Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    /** The host it last reported to, so leaving can take it off. */
    var anchors: TooltipAnchors? = null

    override fun onDetach() {
        anchors?.remove(tooltip, this)
        anchors = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val host = currentValueOf(LocalTooltipAnchors)
        if (host !== anchors) anchors?.remove(tooltip, this)
        anchors = host ?: return
        val full = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
        host.put(tooltip, AnchorBounds(full, coordinates.boundsInWindow(), side, this))
    }
}

/** Where the bubble went: which side, and where its arrow points, from its own left edge. */
internal data class BubblePlacement(val below: Boolean, val arrowX: Float)

/**
 * The bubble (#190), in its own window over the screen: not focusable, so TalkBack and taps
 * outside it reach the screen as before (no focus trap), and it is announced as it appears. The
 * whole bubble is its one button ("Got it" is its visible label).
 */
@Composable
private fun TooltipBubble(text: String, tag: String, anchor: Rect, side: TooltipSide, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    var placement by remember { mutableStateOf<BubblePlacement?>(null) }
    val provider = remember(anchor, side, density) {
        BubblePositionProvider(anchor, side, density) { placement = it }
    }
    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        val colors = MaterialTheme.colorScheme
        val shape = BubbleShape(placement, density)
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(colors.inverseSurface, shape)
                .clickable(onClickLabel = stringResource(R.string.cd_dismiss_tip), role = Role.Button, onClick = onDismiss)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(tag)
                .padding(top = if (placement?.below != false) ARROW else 0.dp, bottom = if (placement?.below == false) ARROW else 0.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.inverseOnSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.tooltip_got_it),
                style = MaterialTheme.typography.labelLarge,
                color = colors.inversePrimary,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

private val ARROW = 8.dp
internal val MARGIN = 12.dp

/** Places the bubble by [anchor] (window coordinates), inside the window, and says where it went. */
internal class BubblePositionProvider(
    private val anchor: Rect,
    private val side: TooltipSide,
    density: Density,
    private val onPlaced: (BubblePlacement) -> Unit
) : PopupPositionProvider {
    private val margin = with(density) { MARGIN.roundToPx() }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val x = (anchor.center.x - width / 2f).roundToInt()
            .coerceIn(margin, max(margin, windowSize.width - margin - width))
        val spaceAbove = anchor.top - margin
        val spaceBelow = windowSize.height - margin - anchor.bottom
        val below = when (side) {
            TooltipSide.BELOW -> true
            TooltipSide.ABOVE -> false
            TooltipSide.AUTO -> spaceBelow >= height || spaceBelow >= spaceAbove
        }
        val y = if (below) anchor.bottom.roundToInt() else (anchor.top - height).roundToInt()
        onPlaced(BubblePlacement(below, anchor.center.x - x))
        return IntOffset(x, y.coerceIn(0, max(0, windowSize.height - height)))
    }
}

/** A rounded box with an arrow on the side facing the control, once placed. */
private class BubbleShape(private val placement: BubblePlacement?, density: Density) : Shape {
    private val arrow = with(density) { ARROW.toPx() }
    private val corner = with(density) { 12.dp.toPx() }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val below = placement?.below != false
        val top = if (below) arrow else 0f
        val bottom = if (below) size.height else size.height - arrow
        val path = Path().apply {
            addRoundRect(RoundRect(Rect(0f, top, size.width, bottom), CornerRadius(corner)))
            placement?.let {
                val x = it.arrowX.coerceIn(corner + arrow, max(corner + arrow, size.width - corner - arrow))
                if (below) {
                    moveTo(x - arrow, top + 1f)
                    lineTo(x, 0f)
                    lineTo(x + arrow, top + 1f)
                } else {
                    moveTo(x - arrow, bottom - 1f)
                    lineTo(x, size.height)
                    lineTo(x + arrow, bottom - 1f)
                }
                close()
            }
        }
        return Outline.Generic(path)
    }
}
