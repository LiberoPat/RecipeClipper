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
