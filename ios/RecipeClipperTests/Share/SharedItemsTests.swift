import UniformTypeIdentifiers
import XCTest
@testable import RecipeClipper

/// Reading what a share carried, from real item providers like the ones iOS hands the
/// extension.
final class SharedItemsTests: XCTestCase {

    func testAUrlAttachmentIsTheLink() async {
        let provider = NSItemProvider(item: URL(string: "https://example.com/soup?x=1")! as NSURL,
                                      typeIdentifier: UTType.url.identifier)
        let input = await SharedItems.read(from: [provider])
        XCTAssertEqual(input, SharedInput(url: "https://example.com/soup?x=1"))
    }

    func testTheFirstLinkInSharedTextIsTheLink() async {
        let provider = NSItemProvider(item: "Look at this! https://example.com/soup and more" as NSString,
                                      typeIdentifier: UTType.plainText.identifier)
        let input = await SharedItems.read(from: [provider])
        XCTAssertEqual(input, SharedInput(url: "https://example.com/soup"))
    }

    func testAUrlBeatsText() async {
        let text = NSItemProvider(item: "https://example.com/from-text" as NSString,
                                  typeIdentifier: UTType.plainText.identifier)
        let url = NSItemProvider(item: URL(string: "https://example.com/from-url")! as NSURL,
                                 typeIdentifier: UTType.url.identifier)
        let input = await SharedItems.read(from: [text, url])
        XCTAssertEqual(input?.url, "https://example.com/from-url")
    }

    func testANonWebUrlOrPlainWordsIsNothing() async {
        let file = NSItemProvider(item: URL(string: "file:///etc/hosts")! as NSURL,
                                  typeIdentifier: UTType.url.identifier)
        let words = NSItemProvider(item: "no link here" as NSString, typeIdentifier: UTType.plainText.identifier)
        let input = await SharedItems.read(from: [file, words])
        XCTAssertNil(input)
    }

    // MARK: - Safari's JavaScript preprocessing result (#35)

    /// A real property-list item provider, shaped exactly as `NSExtensionJavaScriptPreprocessingFile`
    /// delivery does: the type identifier to load and, inside the loaded dictionary, the same
    /// key holding what `completionFunction` was called with.
    private func preprocessingProvider(_ payload: [String: Any]) -> NSItemProvider {
        NSItemProvider(
            item: [NSExtensionJavaScriptPreprocessingResultsKey: payload] as NSDictionary,
            typeIdentifier: NSExtensionJavaScriptPreprocessingResultsKey
        )
    }

    func testAPreprocessingResultIsTheResolvedLinkAndTheRenderedPage() async {
        let provider = preprocessingProvider(["url": "https://example.com/soup", "html": "<html>rendered</html>"])
        let input = await SharedItems.read(from: [provider])
        XCTAssertEqual(input, SharedInput(url: "https://example.com/soup", page: "<html>rendered</html>"))
    }

    /// Safari hands over both: the plain URL attachment every app sends, and the preprocessing
    /// result. The rendered page is strictly more (it's what the fetch would have to guess at),
    /// so it wins.
    func testAPreprocessingResultBeatsAPlainUrlAttachment() async {
        let url = NSItemProvider(item: URL(string: "https://example.com/plain")! as NSURL,
                                 typeIdentifier: UTType.url.identifier)
        let page = preprocessingProvider(["url": "https://example.com/rendered", "html": "<html>rendered</html>"])
        let input = await SharedItems.read(from: [url, page])
        XCTAssertEqual(input, SharedInput(url: "https://example.com/rendered", page: "<html>rendered</html>"))
    }

    /// The script ran (an `NSExtensionActivationSupportsWebPageWithMaxCount` share) but somehow
    /// carried no URL: falls through to whatever else was shared, same as no preprocessing at all.
    func testAPreprocessingResultWithNoUrlFallsBackToTheUrlAttachment() async {
        let url = NSItemProvider(item: URL(string: "https://example.com/plain")! as NSURL,
                                 typeIdentifier: UTType.url.identifier)
        let page = preprocessingProvider(["html": "<html>rendered</html>"])
        let input = await SharedItems.read(from: [page, url])
        XCTAssertEqual(input, SharedInput(url: "https://example.com/plain"))
    }

    /// Not a web page (a non-Safari share, or Safari sharing something that isn't a page): no
    /// preprocessing result at all, same as today.
    func testNoPreprocessingResultIsJustTheUrl() async {
        let provider = NSItemProvider(item: URL(string: "https://example.com/soup")! as NSURL,
                                      typeIdentifier: UTType.url.identifier)
        let input = await SharedItems.read(from: [provider])
        XCTAssertEqual(input, SharedInput(url: "https://example.com/soup"))
        XCTAssertNil(input?.page)
    }
}
