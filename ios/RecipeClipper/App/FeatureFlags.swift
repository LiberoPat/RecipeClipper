import Foundation

/// Features that ship dark. Each is one constant, here and nowhere else.
enum FeatureFlags {
    /// The bottom tab bar, Recipes · Week · Groceries · Pantry (#47). Off until the Week tab
    /// (#49) has something in it; off, the app is the single stack it was before the shell.
    /// Android's twin is `BuildConfig.MEAL_PLAN_TABS`: turn both on together.
    static let mealPlanTabsDefault = false

    static var mealPlanTabs: Bool {
        #if DEBUG
        // Debug builds only, for the UI tests of the tab bar: `-mealPlanTabs` turns it on.
        if ProcessInfo.processInfo.arguments.contains(mealPlanTabsLaunchFlag) { return true }
        #endif
        return mealPlanTabsDefault
    }

    static let mealPlanTabsLaunchFlag = "-mealPlanTabs"
}
