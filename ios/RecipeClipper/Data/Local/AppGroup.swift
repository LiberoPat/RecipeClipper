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

/// A feature flag's key in the App Group suite, where the app mirrors it for its extensions,
/// which never see the flags (`AppContainer.startMirroring`).
protocol MirroredFlag {
    static var key: String { get }
}

/// A flag as an extension reads it: the app's mirror of it in the App Group suite, under
/// `F.key`. On until the app has written it, as every flag's default is.
struct DefaultsFlagSwitch<F: MirroredFlag>: @unchecked Sendable {
    static var key: String { F.key }
    let defaults: UserDefaults

    var isOn: Bool { defaults.object(forKey: F.key) as? Bool ?? true }

    func store(_ on: Bool) {
        if defaults.object(forKey: F.key) as? Bool != on { defaults.set(on, forKey: F.key) }
    }
}

/// Whether the home-screen widgets show the plan and the list (the `widgets` flag, #309), as
/// the widget extension reads it.
enum WidgetsFlag: MirroredFlag { static let key = "widgets_on" }
typealias DefaultsWidgetsSwitch = DefaultsFlagSwitch<WidgetsFlag>

/// Whether Reddit links go to the Reddit source (the `reddit` flag, #11), as the share
/// extension reads it.
enum RedditFlag: MirroredFlag { static let key = "reddit_on" }
typealias DefaultsRedditSwitch = DefaultsFlagSwitch<RedditFlag>

/// Whether shared text goes to the editor (the `shareText` flag, #306), as the share extension
/// reads it.
enum ShareTextFlag: MirroredFlag { static let key = "share_text_on" }
typealias DefaultsShareTextSwitch = DefaultsFlagSwitch<ShareTextFlag>

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

/// Text the share extension was given that holds a recipe, or is neither a link nor a list
/// (#306), handed to the app as its reading (`SharedText.read`, done once in the extension,
/// #334), as JSON. The review needs the app (never saved unchecked), which the extension can't
/// open (#19), so it leaves the reading here and its card says to open Recipe Clipper; the app,
/// becoming active within `window`, opens the editor on it, once. Each new share replaces or
/// clears it, as `PendingClip` does.
struct PendingText: @unchecked Sendable {
    static let textKey = "pending_text"
    static let atKey = "pending_text_at"
    static let window: Int64 = PendingClip.window
    let defaults: UserDefaults

    func put(_ reading: SharedText.Reading, at now: Int64) {
        guard let data = try? JSONEncoder().encode(reading) else { return }
        defaults.set(data, forKey: Self.textKey)
        defaults.set(now, forKey: Self.atKey)
    }

    func clear() {
        defaults.removeObject(forKey: Self.textKey)
        defaults.removeObject(forKey: Self.atKey)
    }

    /// The reading left within the window, taken so that it opens once; an older one is dropped.
    func take(now: Int64) -> SharedText.Reading? {
        guard let data = defaults.data(forKey: Self.textKey) else { return nil }
        let at = (defaults.object(forKey: Self.atKey) as? NSNumber)?.int64Value ?? 0
        clear()
        let age = now - at
        guard age >= 0 && age <= Self.window else { return nil }
        return try? JSONDecoder().decode(SharedText.Reading.self, from: data)
    }
}
