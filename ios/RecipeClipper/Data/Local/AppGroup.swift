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

/// What a hand-off from the share extension stores: a value and the time it was left, under
/// `valueKey` and `atKey` in the App Group suite.
protocol HandOffKind {
    associatedtype Value: Codable
    static var valueKey: String { get }
    static var atKey: String { get }
}

/// Something the share extension hands to the app, as JSON. The extension can't open the app
/// (#19), so it leaves the value here and its card says to open Recipe Clipper; the app,
/// becoming active within `window`, takes it, once. Each new share replaces or clears it.
struct PendingHandOff<K: HandOffKind>: @unchecked Sendable {
    /// Long enough to switch to the app; short enough that opening it later, for something else,
    /// doesn't land on a hand-off nobody remembers asking for.
    static var window: Int64 { 10 * 60 * 1000 }
    let defaults: UserDefaults

    func put(_ value: K.Value, at now: Int64) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        defaults.set(data, forKey: K.valueKey)
        defaults.set(now, forKey: K.atKey)
    }

    func clear() {
        defaults.removeObject(forKey: K.valueKey)
        defaults.removeObject(forKey: K.atKey)
    }

    /// The value left within the window, taken so that it is used once; an older one is dropped.
    func take(now: Int64) -> K.Value? {
        guard let data = defaults.data(forKey: K.valueKey) else { return nil }
        let at = (defaults.object(forKey: K.atKey) as? NSNumber)?.int64Value ?? 0
        clear()
        let age = now - at
        guard age >= 0 && age <= Self.window else { return nil }
        return try? JSONDecoder().decode(K.Value.self, from: data)
    }
}

/// A Reddit post the share extension was blocked from reading (#213): its URL, which the app
/// opens "Clip it yourself" on, with the note.
enum ClipHandOff: HandOffKind {
    typealias Value = String
    static let valueKey = "pending_clip_url"
    static let atKey = "pending_clip_at"
}
typealias PendingClip = PendingHandOff<ClipHandOff>

/// Text the share extension was given that holds a recipe, or is neither a link nor a list
/// (#306), as its reading (`SharedText.read`, done once in the extension, #334). The review
/// needs the app (never saved unchecked), which opens the editor on it.
enum TextHandOff: HandOffKind {
    typealias Value = SharedText.Reading
    static let valueKey = "pending_text"
    static let atKey = "pending_text_at"
}
typealias PendingText = PendingHandOff<TextHandOff>
