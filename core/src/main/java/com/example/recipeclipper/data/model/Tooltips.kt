package com.example.recipeclipper.data.model

/** The screens that show tooltips (#190). Cook mode is its own, though it is the recipe screen's. */
enum class TooltipScreen { HOME, RECIPE, COOK, WEEK, GROCERIES, PANTRY, SETTINGS }

/**
 * The tooltips (#190): a small bubble pointing at a control, shown the first time it's reached.
 * In the order each screen shows them; `shared/tooltips.json` lists the same ids and screens for
 * both apps (iOS's `Tooltip`), and `TooltipsTest` fails if this differs from it. Seen once
 * dismissed, stored under [key].
 */
enum class Tooltip(val id: String, val screen: TooltipScreen) {
    HOME_LINK("home_link", TooltipScreen.HOME),
    HOME_NEW_RECIPE("home_new_recipe", TooltipScreen.HOME),
    RECIPE_UNITS("recipe_units", TooltipScreen.RECIPE),
    RECIPE_BOOKMARK("recipe_bookmark", TooltipScreen.RECIPE),
    RECIPE_SHARE("recipe_share", TooltipScreen.RECIPE),
    RECIPE_MENU("recipe_menu", TooltipScreen.RECIPE),
    RECIPE_START_COOKING("recipe_start_cooking", TooltipScreen.RECIPE),
    RECIPE_MADE_THIS("recipe_made_this", TooltipScreen.RECIPE),
    COOK_DONE_NEXT("cook_done_next", TooltipScreen.COOK),
    COOK_TAP_STEP("cook_tap_step", TooltipScreen.COOK),
    COOK_TIMER("cook_timer", TooltipScreen.COOK),
    COOK_INGREDIENTS("cook_ingredients", TooltipScreen.COOK),
    WEEK_ADD("week_add", TooltipScreen.WEEK),
    WEEK_MONTH("week_month", TooltipScreen.WEEK),
    WEEK_MENU("week_menu", TooltipScreen.WEEK),
    GROCERIES_ADD("groceries_add", TooltipScreen.GROCERIES),
    GROCERIES_TICK("groceries_tick", TooltipScreen.GROCERIES),
    GROCERIES_LONG_PRESS("groceries_long_press", TooltipScreen.GROCERIES),
    GROCERIES_DONE_SHOPPING("groceries_done_shopping", TooltipScreen.GROCERIES),
    GROCERIES_MENU("groceries_menu", TooltipScreen.GROCERIES),
    PANTRY_ADD("pantry_add", TooltipScreen.PANTRY),
    PANTRY_IN_STOCK("pantry_in_stock", TooltipScreen.PANTRY),
    PANTRY_MENU("pantry_menu", TooltipScreen.PANTRY),
    SETTINGS_UNITS("settings_units", TooltipScreen.SETTINGS),
    SETTINGS_SHOW_TIPS("settings_show_tips", TooltipScreen.SETTINGS);

    /** Its `unit_preferences` / UserDefaults key, true once seen; the same on iOS. */
    val key: String get() = Tooltips.KEY_PREFIX + id
}

/**
 * One visit to a screen (#190), from when it appears until it's left. [token] names the screen's
 * appearance, so a rotation (the same token) stays the same visit. A visit shows at most one
 * tooltip: [shown] once picked, it stays the visit's, and [closed] once it has gone.
 */
data class TooltipVisit(
    val token: String,
    val screen: TooltipScreen,
    val shown: Tooltip? = null,
    val closed: Boolean = false
)

/**
 * Which tooltip shows now (#190), platform-free; iOS's `Tooltips` is the same.
 *
 * - One at a time: only the current visit's screen shows one, and a visit shows one at most.
 *   Dismissed, the screen's next one waits for a later visit: never chained.
 * - In the catalogue's order, the first not yet seen whose control is on
 *   screen: an anchor scrolled away, or under a sheet or the keyboard, is passed over.
 * - Never before the screen has settled ([SETTLE_MILLIS]) or while something covers it: the
 *   caller says so with `ready`.
 */
object Tooltips {
    const val KEY_PREFIX = "tooltip_"

    /** A screen's first second, when nothing shows: let it settle. */
    const val SETTLE_MILLIS = 1_000L

    fun forScreen(screen: TooltipScreen): List<Tooltip> = Tooltip.entries.filter { it.screen == screen }

    /** The screen [token] appeared: its visit goes on (a rotation), or a new one starts. */
    fun visit(current: TooltipVisit?, token: String, screen: TooltipScreen): TooltipVisit =
        current?.takeIf { it.token == token && it.screen == screen } ?: TooltipVisit(token, screen)

    /** The screen [token] was left: its visit is over. Another screen's goes on. */
    fun leave(current: TooltipVisit?, token: String): TooltipVisit? = current?.takeUnless { it.token == token }

    /**
     * The tooltip to show on [visit]'s screen now, or null. [visible] are the anchors on screen
     * and uncovered; [ready] is false during the first second and while anything covers the
     * screen (a sheet, a dialog, a menu, the keyboard, a snackbar).
     */
    fun current(
        visit: TooltipVisit?,
        seen: Set<Tooltip>,
        visible: Set<Tooltip>,
        ready: Boolean
    ): Tooltip? {
        if (visit == null || visit.closed || !ready) return null
        visit.shown?.let { return it.takeIf { it in visible && it !in seen } }
        return forScreen(visit.screen).firstOrNull {
            it !in seen && it in visible
        }
    }

    /** [tooltip] is showing: it is the visit's one. */
    fun shown(visit: TooltipVisit, tooltip: Tooltip): TooltipVisit =
        if (visit.shown == null) visit.copy(shown = tooltip) else visit

    /** Dismissed ("Got it"), or closed without it (iOS's tap outside): gone for the visit. */
    fun closed(visit: TooltipVisit): TooltipVisit = visit.copy(closed = true)
}
