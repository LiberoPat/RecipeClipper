import Foundation

/// `AppInfo` from the running OS and the app bundle's Info.plist.
struct BundleAppInfo: AppInfo {
    let platform: String
    let appVersion: String

    init(bundle: Bundle = .main, processInfo: ProcessInfo = .processInfo) {
        let os = processInfo.operatingSystemVersion
        let patch = os.patchVersion == 0 ? "" : ".\(os.patchVersion)"
        platform = "iOS \(os.majorVersion).\(os.minorVersion)\(patch)"
        let version = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
        let build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
        appVersion = "\(version) (\(build))"
    }
}
