import SwiftUI
import WidgetKit

/// The widget extension (#309): a running cook-mode timer's Live Activity, and the Tonight and
/// grocery list home-screen widgets. It shares the App Group with the app and the share
/// extension, and reads the database there.
@main
struct RecipeClipperWidgets: WidgetBundle {
    var body: some Widget {
        TimerLiveActivity()
        TonightWidget()
        GroceriesWidget()
    }
}
