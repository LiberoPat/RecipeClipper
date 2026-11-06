import Foundation

/// The widget extension's words (#309): the app's `Strings.swift` accessors for the few keys it
/// shows. `Strings.swift` itself can't come along, since it formats the app's data types; the
/// catalog is compiled into this bundle too, so the keys and translations are the app's.
enum WidgetStrings {
    static func timerRunningTitle(step: Int) -> String { String(localized: "timer_running_title \(step)") }
    static func timerNotificationTitle(step: Int) -> String { String(localized: "timer_notification_title \(step)") }

    // The home-screen widgets.
    /// The app's name, the same in every language: all a widget shows with the flag off.
    static var appName: String { String(localized: "app_name") }
    static var tonight: String { String(localized: "widget_tonight_name") }
    static var tonightDescription: String { String(localized: "widget_tonight_description") }
    static var tonightEmpty: String { String(localized: "widget_tonight_empty") }
    static var groceries: String { String(localized: "tab_groceries") }
    static var groceriesDescription: String { String(localized: "widget_groceries_description") }
    static var groceriesEmpty: String { String(localized: "widget_groceries_empty") }
    static func more(_ count: Int) -> String { String(localized: "widget_more \(count)") }
    /// The count beside the one row a widget has room for. Not words, so not in the catalog.
    static func moreBeside(_ count: Int) -> String { "+\(count)" }
}
