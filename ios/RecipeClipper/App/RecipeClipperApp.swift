import SwiftUI
import UserNotifications
import UIKit

@main
struct RecipeClipperApp: App {
    @State private var container = AppContainer.live()
    @State private var router: Router

    // The notification center holds its delegate weakly, so the app keeps it.
    private static let notificationRouter = NotificationRouter()

    init() {
        // Set before launch finishes, so a tap on a timer notification that cold-starts the
        // app is still delivered (Apple's rule for the delegate).
        let router = Router()
        _router = State(initialValue: router)
        Self.notificationRouter.router = router
        UNUserNotificationCenter.current().delegate = Self.notificationRouter
        // Recipe photos go through a disk-backed cache (50 MB memory, 200 MB disk), so a photo
        // seen once still shows offline. See ImageLoader.
        ImageLoader.configure()
        // SwiftUI's `.tint` on the NavigationStack colours SwiftUI controls only. Alerts,
        // context menus, swipe-action fallbacks and text-field cursors are UIKit and inherit the
        // window's tint, which is system blue unless set here (there is no AccentColor asset).
        UIWindow.appearance().tintColor = Palette.uiAccentText
        // An alert's text field (Rename list) sets its own cursor tint and ignores the
        // window's; checked on the simulator, where it drew a blue caret.
        UITextField.appearance().tintColor = Palette.uiAccentText
    }

    var body: some Scene {
        WindowGroup {
            RootView(container: container, router: router)
        }
    }
}
