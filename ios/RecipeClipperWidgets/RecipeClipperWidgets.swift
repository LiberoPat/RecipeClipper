import SwiftUI
import WidgetKit

/// The widget extension (#309): a running cook-mode timer's Live Activity. It shares the App
/// Group with the app and the share extension.
@main
struct RecipeClipperWidgets: WidgetBundle {
    var body: some Widget {
        TimerLiveActivity()
    }
}
