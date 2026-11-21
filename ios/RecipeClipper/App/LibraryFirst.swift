import Foundation

extension FeatureFlags {
    /// Library first (#375): the Recipes tab opens the library. It needs `oneCookbook` (#345), whose views
    /// strip carries the Lists card that Home's Lists row was the only way to without it, so with that off
    /// the tab keeps opening Home.
    var libraryFirst: Bool { isOn(.libraryFirst) && isOn(.oneCookbook) }
}
