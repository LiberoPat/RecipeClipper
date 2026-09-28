import XCTest

/// CLAUDE.md: ViewModels never import SwiftUI or UIKit. Found by what a file declares, not its
/// name, so a ViewModel kept beside its views (as `TooltipsViewModel` once was) is caught.
/// Android's twin is `ViewModelImportsTest`.
final class ViewModelImportsTests: XCTestCase {

    func testNoFileDeclaringAViewModelImportsSwiftUIOrUIKit() throws {
        // The app's sources, found from this file's path (the tests run on this Mac's simulator).
        let sources = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("RecipeClipper")
        let viewModel = try NSRegularExpression(pattern: #"\bclass\s+\w+ViewModel\b"#)
        let forbidden = try NSRegularExpression(pattern: #"^\s*(@\w+\s+)*import\s+(SwiftUI|UIKit)\b"#)
        let files = try XCTUnwrap(FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil))
            .compactMap { $0 as? URL }
            .filter { $0.pathExtension == "swift" }
            .compactMap { url in (try? String(contentsOf: url, encoding: .utf8)).map { (url.lastPathComponent, $0) } }
            .filter { _, text in viewModel.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil }
        XCTAssertGreaterThan(files.count, 5, "no ViewModels found at \(sources.path)")
        let offenders = files.flatMap { name, text in
            text.components(separatedBy: .newlines)
                .filter { forbidden.firstMatch(in: $0, range: NSRange($0.startIndex..., in: $0)) != nil }
                .map { "\(name): \($0.trimmingCharacters(in: .whitespaces))" }
        }
        XCTAssertEqual(offenders, [], "move the ViewModel to its own file")
    }
}
