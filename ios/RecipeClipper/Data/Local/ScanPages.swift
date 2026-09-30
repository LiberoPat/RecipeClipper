import Foundation

/// A scan's pages (#226) on disk while they are read and checked: the pictures the camera, the
/// library or the share extension handed over, as files in `Scans/` in the App Group container
/// (so the extension and the app see the same ones). Never kept: each new scan replaces the last
/// one's, a saved scan clears them, and the app's launch sweeps any left over an hour.
/// Android reads its pages where they are (content URIs) instead, so it has no twin.
struct ScanPages: @unchecked Sendable {
    /// A scan's pages: enough for a card's front and back, or a two-page recipe.
    static let maxPages = 6

    let directory: URL
    private let files = FileManager.default

    init(directory: URL) {
        self.directory = directory
    }

    /// `Scans/` in the App Group container; the app's caches when this build has no container.
    static func shared() -> ScanPages {
        let base = AppGroup.containerURL
            ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return ScanPages(directory: base.appendingPathComponent("Scans", isDirectory: true))
    }

    /// Replaces the last scan's pages with `pictures`, in order, at most `maxPages`; their file
    /// URLs, one that couldn't be written left out.
    func stage(_ pictures: [Data]) -> [String] {
        clear()
        return pictures.prefix(Self.maxPages).enumerated().compactMap { index, data in write(data, page: index) }
    }

    /// Page `index` of a scan being staged, from its bytes; its file URL, or nil.
    func write(_ data: Data, page index: Int) -> String? {
        let file = newFile(index)
        return (try? data.write(to: file, options: .atomic)) == nil ? nil : file.absoluteString
    }

    /// Page `index` of a scan being staged, copied from a file (the share extension copies what
    /// the sharing app handed over without holding it in memory); its file URL, or nil.
    func copy(_ source: URL, page index: Int) -> String? {
        let file = newFile(index)
        return (try? files.copyItem(at: source, to: file)) == nil ? nil : file.absoluteString
    }

    /// A page's file URL from its file name, only if it is one of this folder's.
    func page(named name: String) -> String? {
        guard !name.isEmpty, !name.contains("/"), name != "..", name != "." else { return nil }
        let file = directory.appendingPathComponent(name)
        return files.fileExists(atPath: file.path) ? file.absoluteString : nil
    }

    /// Every page gone.
    func clear() {
        try? files.removeItem(at: directory)
    }

    /// Pages last written more than `age` seconds before `now` go: a scan left behind by a
    /// review nobody finished, or by a share whose app was never opened.
    func sweep(olderThan age: TimeInterval, now: Date = Date()) {
        guard let names = try? files.contentsOfDirectory(atPath: directory.path) else { return }
        for name in names {
            let file = directory.appendingPathComponent(name)
            let written = (try? files.attributesOfItem(atPath: file.path)[.modificationDate] as? Date) ?? .distantPast
            if now.timeIntervalSince(written) > age { try? files.removeItem(at: file) }
        }
    }

    private func newFile(_ index: Int) -> URL {
        try? files.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("page-\(index + 1)-\(UUID().uuidString).img")
    }
}

/// Images the share extension was given (#226), handed to the app. The extension can't open
/// the app (#19), and the review needs it (never saved unchecked), so it leaves the pages in
/// `ScanPages` and their names here, and its card says to open Recipe Clipper; the app,
/// becoming active within `window`, opens the scan's review on them, once. Each new share
/// replaces or clears it, as `PendingClip` does for a blocked Reddit post (#213).
struct PendingScan: @unchecked Sendable {
    static let pagesKey = "pending_scan_pages"
    static let atKey = "pending_scan_at"
    static let window: Int64 = PendingClip.window
    let defaults: UserDefaults
    let pages: ScanPages

    /// Leaves the pages (file URLs in `pages`' folder) for the app.
    func put(_ staged: [String], at now: Int64) {
        let names = staged.compactMap { URL(string: $0)?.lastPathComponent }
        defaults.set(names, forKey: Self.pagesKey)
        defaults.set(now, forKey: Self.atKey)
    }

    func clear() {
        defaults.removeObject(forKey: Self.pagesKey)
        defaults.removeObject(forKey: Self.atKey)
    }

    /// The pages left within the window, taken so that they open once; older ones are dropped.
    func take(now: Int64) -> [String]? {
        guard let names = defaults.stringArray(forKey: Self.pagesKey) else { return nil }
        let at = (defaults.object(forKey: Self.atKey) as? NSNumber)?.int64Value ?? 0
        clear()
        let age = now - at
        guard age >= 0, age <= Self.window else { return nil }
        let found = names.compactMap(pages.page(named:))
        return found.isEmpty ? nil : found
    }
}

/// Whether images shared in are scanned (the `photoText` flag, #226), as the share extension
/// reads it: the app mirrors the flag into the App Group suite under `photo_text_on`, since the
/// extension never sees the flags. On until the app has written it, as the flag's default is.
struct DefaultsPhotoTextSwitch: @unchecked Sendable {
    static let key = "photo_text_on"
    let defaults: UserDefaults

    var isOn: Bool { defaults.object(forKey: Self.key) as? Bool ?? true }

    func store(_ on: Bool) {
        if defaults.object(forKey: Self.key) as? Bool != on { defaults.set(on, forKey: Self.key) }
    }
}
