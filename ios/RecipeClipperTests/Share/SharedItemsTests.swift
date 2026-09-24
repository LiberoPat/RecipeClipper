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
}
