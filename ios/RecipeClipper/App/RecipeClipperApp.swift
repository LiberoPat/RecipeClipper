import SwiftUI
import UIKit

@main
struct RecipeClipperApp: App {
    @State private var container = AppContainer.live()
    @State private var router = Router()

    init() {
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
