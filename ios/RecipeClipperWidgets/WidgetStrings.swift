import Foundation

/// The widget extension's words (#309): the app's `Strings.swift` accessors for the few keys it
/// shows. `Strings.swift` itself can't come along, since it formats the app's data types; the
/// catalog is compiled into this bundle too, so the keys and translations are the app's.
enum WidgetStrings {
    static func timerRunningTitle(step: Int) -> String { String(localized: "timer_running_title \(step)") }
    static func timerNotificationTitle(step: Int) -> String { String(localized: "timer_notification_title \(step)") }
}
