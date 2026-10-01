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

/// Whether Reddit links go to the Reddit source (the `reddit` flag, #11), as the share
/// extension reads it: the app mirrors the flag into the App Group suite under `reddit_on`,
/// since the extension never sees the flags. On until the app has written it, as the flag's
/// default is.
struct DefaultsRedditSwitch: @unchecked Sendable {
    static let key = "reddit_on"
    let defaults: UserDefaults

    var isOn: Bool { defaults.object(forKey: Self.key) as? Bool ?? true }

    func store(_ on: Bool) {
        if defaults.object(forKey: Self.key) as? Bool != on { defaults.set(on, forKey: Self.key) }
    }
}

/// A Reddit post the share extension was blocked from reading (#213), handed to the app. The
/// extension can't open the app (#19), so it leaves the post here and its card says to open
/// Recipe Clipper; the app, becoming active within `window`, opens "Clip it yourself" on it,
/// with the note, once. Each new share replaces or clears it.
struct PendingClip: @unchecked Sendable {
    static let urlKey = "pending_clip_url"
    static let atKey = "pending_clip_at"
    /// Long enough to switch to the app; short enough that opening it later, for something else,
    /// doesn't land on a clip nobody remembers asking for.
    static let window: Int64 = 10 * 60 * 1000
    let defaults: UserDefaults

    func put(_ url: String, at now: Int64) {
        defaults.set(url, forKey: Self.urlKey)
        defaults.set(now, forKey: Self.atKey)
    }

    func clear() {
        defaults.removeObject(forKey: Self.urlKey)
        defaults.removeObject(forKey: Self.atKey)
    }

    /// The post left within the window, taken so that it opens once; an older one is dropped.
    func take(now: Int64) -> String? {
        guard let url = defaults.string(forKey: Self.urlKey) else { return nil }
        let at = (defaults.object(forKey: Self.atKey) as? NSNumber)?.int64Value ?? 0
        clear()
        let age = now - at
        return age >= 0 && age <= Self.window ? url : nil
    }
}
