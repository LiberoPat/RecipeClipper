#if DEBUG
import Foundation

/// Debug builds only: launch arguments that drive the app to a screen without touch, for
/// simulator screenshots (`simctl openurl` needs a tap on the system "Open in…?" prompt).
///   -debugOpen <recipe url>   push the import screen for that link
///   -debugRoute history|lists|settings|list:<id>
///   -debugRename              open the rename alert once a list detail has loaded
///   -debugCook                start cook mode once the recipe has loaded
enum DebugLaunch {
    private static var arguments: [String] { ProcessInfo.processInfo.arguments }

    private static func value(after flag: String) -> String? {
        guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
        return arguments[index + 1]
    }

    static var initialPath: [Route] {
        var path: [Route] = []
        switch value(after: "-debugRoute") {
        case "history": path.append(.history)
        case "lists": path.append(.lists)
        case "settings": path.append(.settings)
        case let route? where route.hasPrefix("list:"):
            if let id = Int64(route.dropFirst("list:".count)) { path.append(contentsOf: [.lists, .listDetail(id: id)]) }
        default: break
        }
        if let url = value(after: "-debugOpen") { path.append(.importUrl(url)) }
        return path
    }

    static var autoCook: Bool { arguments.contains("-debugCook") }
    static var autoRename: Bool { arguments.contains("-debugRename") }
}
#endif
