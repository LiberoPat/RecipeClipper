import SwiftUI
import UIKit
import XCTest
@testable import RecipeClipper

/// Saving a recipe turns its outlined bookmark into a filled one, and the glyph must not move
/// ("the bookmark moves to the right when it turns red"). Both are `BookmarkGlyph`, one slot the
/// width of the button's touch target, glyph centred, and the row puts nothing between that slot
/// and the title's column in either state. Snapshot-free and independent of how the symbol draws:
/// what is measured is the size SwiftUI lays the slot out at, filled and outlined.
@MainActor
final class BookmarkSlotTests: XCTestCase {

    /// The size a view is laid out at when offered a generous space: a 44-wide slot takes 44, not
    /// the glyph's own width.
    private func laidOutSize<V: View>(_ view: V) -> CGSize {
        let host = UIHostingController(rootView: view)
        return host.sizeThatFits(in: CGSize(width: 300, height: 300))
    }

    func testTheFilledAndTheOutlinedBookmarkTakeTheSameSlot() {
        let outlined = laidOutSize(BookmarkGlyph(filled: false))
        let filled = laidOutSize(BookmarkGlyph(filled: true))
        XCTAssertEqual(outlined.width, BookmarkGlyph.slot.width, accuracy: 0.5)
        XCTAssertEqual(filled.width, outlined.width, accuracy: 0.5, "the filled bookmark's slot is not the button's")
        XCTAssertEqual(filled.height, outlined.height, accuracy: 0.5)
    }

    func testTheSlotIsTheTouchTargetSize() {
        XCTAssertGreaterThanOrEqual(BookmarkGlyph.slot.width, 44)
        XCTAssertGreaterThanOrEqual(BookmarkGlyph.slot.height, 44)
    }

    /// The glyph is centred in its slot: a slot offered more room than it needs stays 44 wide
    /// (the glyph never stretches or drifts to an edge with the row's width).
    func testTheSlotDoesNotGrowWithTheRoomItIsOffered() {
        for filled in [false, true] {
            let host = UIHostingController(rootView: BookmarkGlyph(filled: filled))
            let narrow = host.sizeThatFits(in: CGSize(width: 100, height: 100))
            let wide = host.sizeThatFits(in: CGSize(width: 400, height: 400))
            XCTAssertEqual(narrow.width, wide.width, accuracy: 0.5)
        }
    }
}
