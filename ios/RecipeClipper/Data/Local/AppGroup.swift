import Foundation

/// The App Group the app and the share extension share: the recipe database and the settings
/// suite live in its container, so a recipe the extension saves is one the app shows.
///
/// The identifier must match both targets' `.entitlements` files (generated from
/// `project.yml`), and the group must be registered on both App IDs once there is a
/// development team (docs/release.md).
enum AppGroup {
    static let identifier = "group.com.liberopat.recipeclipper"

    /// The group's shared container, or nil when this build isn't entitled to it (a
    /// misconfigured or unsigned build). The app then falls back to its own sandbox; the
    /// extension can't save anything the app would see.
    static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }
}

/// Whether the grocery list and the pantry exist (the `mealPlan` flag, #47), as the share
/// extension reads it (#149): only then does a list shared in offer "Add this list". The app
/// mirrors the flag into the App Group suite under `groceries_on`, since the extension never
/// sees the flags; off until the app has written it.
struct DefaultsGroceriesSwitch: @unchecked Sendable {
    static let key = "groceries_on"
    let defaults: UserDefaults

    var isOn: Bool { defaults.bool(forKey: Self.key) }

    func store(_ on: Bool) {
        if defaults.object(forKey: Self.key) as? Bool != on { defaults.set(on, forKey: Self.key) }
    }
}
