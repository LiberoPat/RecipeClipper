import Foundation
import UserNotifications

/// Handles the timer notifications: a tap opens that recipe in cook mode (an expiry reminder's,
/// #52, opens the Pantry tab), and one arriving
/// while the app is in front shows as a banner unless that recipe is already on screen, where
/// the in-app beep sounds instead. Installed as the notification center's delegate at launch,
/// before the app finishes launching, so a tap that cold-starts the app is still delivered.
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate {
    static let recipeIdKey = "recipeId"
    /// A tab to open on a tap: an expiry reminder's `AppTab.pantry` (#52).
    static let openTabKey = "openTab"

    @MainActor weak var router: Router?

    private static func recipeId(_ notification: UNNotification) -> Int64? {
        (notification.request.content.userInfo[recipeIdKey] as? NSNumber)?.int64Value
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let id = Self.recipeId(response.notification)
        let tab = (response.notification.request.content.userInfo[Self.openTabKey] as? String).flatMap(AppTab.init)
        Task { @MainActor in
            if let id { self.router?.openInRecipes(.cookRecipe(id: id)) }
            if let tab { self.router?.select(tab) }
            completionHandler()
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let id = Self.recipeId(notification)
        Task { @MainActor in
            completionHandler(id != nil && VisibleRecipe.id == id ? [] : [.banner, .list, .sound])
        }
    }
}

/// The recipe whose screen is showing, if any: set by `RecipeScreen`, read by
/// `NotificationRouter` (Android's `timers.VisibleRecipe`).
@MainActor
enum VisibleRecipe {
    static var id: Int64?

    static func clear(_ recipeId: Int64) {
        if id == recipeId { id = nil }
    }
}
