import Foundation

/// The screens that show tooltips (#190). Cook mode is its own, though it is the recipe screen's.
/// Android's `TooltipScreen`.
enum TooltipScreen: String, CaseIterable {
    case home, recipe, cook, week, groceries, pantry, settings
}

/// The tooltips (#190): a small bubble pointing at a control, shown the first time it's reached.
/// In the order each screen shows them; `shared/tooltips.json` lists the same ids (the raw
/// values) and screens for both apps (Android's `Tooltip`), and `TooltipsTests` fails if this
/// differs from it. Seen once dismissed, stored under `key`.
enum Tooltip: String, CaseIterable {
    case homeLink = "home_link"
    case homeNewRecipe = "home_new_recipe"
    case recipeUnits = "recipe_units"
    case recipeBookmark = "recipe_bookmark"
    case recipeShare = "recipe_share"
    case recipeMenu = "recipe_menu"
    case recipeStartCooking = "recipe_start_cooking"
    case recipeMadeThis = "recipe_made_this"
    case cookDoneNext = "cook_done_next"
    case cookTapStep = "cook_tap_step"
    case cookTimer = "cook_timer"
    case cookIngredients = "cook_ingredients"
    case weekAdd = "week_add"
    case weekMonth = "week_month"
    case weekMenu = "week_menu"
    case groceriesAdd = "groceries_add"
    case groceriesTick = "groceries_tick"
    case groceriesLongPress = "groceries_long_press"
    case groceriesDoneShopping = "groceries_done_shopping"
    case groceriesMenu = "groceries_menu"
    case pantryAdd = "pantry_add"
    case pantryInStock = "pantry_in_stock"
    case pantryMenu = "pantry_menu"
    case settingsUnits = "settings_units"
    case settingsShowTips = "settings_show_tips"

    var id: String { rawValue }

    /// Its UserDefaults / `unit_preferences` key, true once seen; the same on Android.
    var key: String { Tooltips.keyPrefix + rawValue }

    var screen: TooltipScreen {
        switch self {
        case .homeLink, .homeNewRecipe: .home
        case .recipeUnits, .recipeBookmark, .recipeShare, .recipeMenu, .recipeStartCooking,
             .recipeMadeThis: .recipe
        case .cookDoneNext, .cookTapStep, .cookTimer, .cookIngredients: .cook
        case .weekAdd, .weekMonth, .weekMenu: .week
        case .groceriesAdd, .groceriesTick, .groceriesLongPress, .groceriesDoneShopping, .groceriesMenu: .groceries
        case .pantryAdd, .pantryInStock, .pantryMenu: .pantry
        case .settingsUnits, .settingsShowTips: .settings
        }
    }
}

/// One visit to a screen (#190), from when it appears until it's left. `token` names the
/// screen's appearance. A visit shows at most one tooltip: `shown` once picked, it stays the
/// visit's, and `closed` once it has gone. Android's `TooltipVisit`.
struct TooltipVisit: Equatable {
    let token: String
    let screen: TooltipScreen
    var shown: Tooltip?
    var closed = false
}

/// Which tooltip shows now (#190), platform-free; Android's `Tooltips` is the same.
///
/// - One at a time: only the current visit's screen shows one, and a visit shows one at most.
///   Dismissed, the screen's next one waits for a later visit: never chained.
/// - In the catalogue's order, the first not yet seen whose control is on screen: an anchor scrolled away, or under a sheet or the keyboard, is passed over.
/// - Never before the screen has settled (`settleSeconds`) or while something covers it: the
///   caller says so with `ready`.
enum Tooltips {
    static let keyPrefix = "tooltip_"

    /// A screen's first second, when nothing shows: let it settle.
    static let settleSeconds: Double = 1

    static func forScreen(_ screen: TooltipScreen) -> [Tooltip] {
        Tooltip.allCases.filter { $0.screen == screen }
    }

    /// The screen `token` appeared: its visit goes on, or a new one starts.
    static func visit(_ current: TooltipVisit?, token: String, screen: TooltipScreen) -> TooltipVisit {
        if let current, current.token == token, current.screen == screen { return current }
        return TooltipVisit(token: token, screen: screen)
    }

    /// The screen `token` was left: its visit is over. Another screen's goes on.
    static func leave(_ current: TooltipVisit?, token: String) -> TooltipVisit? {
        current?.token == token ? nil : current
    }

    /// The tooltip to show on `visit`'s screen now, or nil. `visible` are the anchors on screen
    /// and uncovered; `ready` is false during the first second and while anything covers the
    /// screen (a sheet, a dialog, a menu, the keyboard).
    static func current(
        _ visit: TooltipVisit?,
        seen: Set<Tooltip>,
        visible: Set<Tooltip>,
        ready: Bool
    ) -> Tooltip? {
        guard let visit, !visit.closed, ready else { return nil }
        if let shown = visit.shown {
            return visible.contains(shown) && !seen.contains(shown) ? shown : nil
        }
        return forScreen(visit.screen).first { tooltip in
            !seen.contains(tooltip) && visible.contains(tooltip)
        }
    }

    /// `tooltip` is showing: it is the visit's one.
    static func shown(_ visit: TooltipVisit, _ tooltip: Tooltip) -> TooltipVisit {
        guard visit.shown == nil else { return visit }
        var shown = visit
        shown.shown = tooltip
        return shown
    }

    /// Dismissed ("Got it"), or closed without it (a tap outside the popover): gone for the visit.
    static func closed(_ visit: TooltipVisit) -> TooltipVisit {
        var closed = visit
        closed.closed = true
        return closed
    }
}
