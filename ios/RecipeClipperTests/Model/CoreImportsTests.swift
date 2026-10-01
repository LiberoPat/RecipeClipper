import XCTest

/// The pure logic imports Foundation and nothing else (#238). On Android it is the `:core`
/// module, where the compiler enforces it: core has no Android to reach. iOS keeps it in the app
/// target (a framework would have needed `public` on hundreds of declarations), so this test
/// enforces it over the same code: everything in `Data/Model`, and every Swift file named like a
/// Kotlin file in `core/` (the parsers, `RecipeRenderer`), so a file Android moves to `:core` is
/// checked here too. Like `ViewModelImportsTests`, it reads the sources from this file's path
/// (the tests run on this Mac's simulator).
final class CoreImportsTests: XCTestCase {

    /// What core code may import. Add a module only if it is as free of UI and platform as
    /// Foundation (#238).
    private let allowed: Set<String> = ["Foundation"]

    func testCoreCodeImportsOnlyFoundation() throws {
        let ios = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let sources = ios.appendingPathComponent("RecipeClipper")
        let kotlinCore = ios.deletingLastPathComponent().appendingPathComponent("core/src/main/java")

        let coreNames = Set(try files(in: kotlinCore, extension: "kt").map { $0.deletingPathExtension().lastPathComponent })
        XCTAssertGreaterThan(coreNames.count, 20, "no Kotlin core found at \(kotlinCore.path)")

        let checked = try files(in: sources, extension: "swift").filter {
            $0.path.contains("/Data/Model/") || coreNames.contains($0.deletingPathExtension().lastPathComponent)
        }
        let names = Set(checked.map(\.lastPathComponent))
        // Both rules found something: the folder, and the parsers and renderer by name.
        XCTAssertTrue(names.isSuperset(of: ["Recipe.swift", "JsonLdRecipeParser.swift", "RecipeRenderer.swift"]),
                      "core files not found at \(sources.path)")

        let importLine = try NSRegularExpression(
            pattern: #"^\s*(?:@\w+(?:\([^)]*\))?\s+)*import\s+(?:(?:typealias|struct|class|enum|protocol|let|var|func)\s+)?(\w+)"#
        )
        let offenders = try checked.flatMap { url in
            try String(contentsOf: url, encoding: .utf8).components(separatedBy: .newlines).compactMap { line -> String? in
                let range = NSRange(line.startIndex..., in: line)
                guard let match = importLine.firstMatch(in: line, range: range),
                      let module = Range(match.range(at: 1), in: line).map({ String(line[$0]) }),
                      !allowed.contains(module) else { return nil }
                return "\(url.lastPathComponent): \(line.trimmingCharacters(in: .whitespaces))"
            }
        }
        XCTAssertEqual(offenders, [], "core code imports Foundation only (#238): keep the platform part outside it")
    }

    private func files(in directory: URL, extension ext: String) throws -> [URL] {
        try XCTUnwrap(FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil))
            .compactMap { $0 as? URL }
            .filter { $0.pathExtension == ext }
    }
}
