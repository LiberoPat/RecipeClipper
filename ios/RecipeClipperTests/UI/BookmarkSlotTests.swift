import SwiftUI
import UIKit
import XCTest
@testable import RecipeClipper

/// Saving a recipe turns its outlined bookmark into a filled one, and the glyph must not move
/// ("the bookmark moves to the right when it turns red"). The outlined one is a button 44 wide, so
/// the filled one takes the same slot, and the text beside it ends in the same place. Snapshot-free:
/// the row is drawn once, saved and unsaved, and only where the ink lands in its trailing 44 points
/// is compared.
@MainActor
final class BookmarkSlotTests: XCTestCase {

    private let scale: CGFloat = 3
    private let width: CGFloat = 390

    private func summary(saved: Bool) -> RecipeSummary {
        RecipeSummary(id: 1, title: "Chicken Adobo", imageUrl: nil, totalTime: "45m", lastViewedAt: 0, isSaved: saved)
    }

    /// The row, drawn on white at a fixed width, as pixels.
    private func render(_ row: LibraryRow) throws -> CGImage {
        let view = row
            .frame(width: width)
            .background(Color.white)
            .environment(\.colorScheme, .light)
        let renderer = ImageRenderer(content: view)
        renderer.scale = scale
        return try XCTUnwrap(renderer.cgImage, "the row drew nothing")
    }

    /// The horizontal extent, in points from the row's trailing edge, of everything in the trailing
    /// `slot` points that isn't the white ground: the bookmark glyph.
    private func inkExtent(_ image: CGImage, slot: CGFloat) throws -> ClosedRange<CGFloat> {
        let w = image.width, h = image.height
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let context = try XCTUnwrap(CGContext(
            data: &pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        let firstColumn = w - Int(slot * scale)
        var minX = Int.max, maxX = Int.min
        for x in firstColumn..<w {
            for y in 0..<h {
                let i = (y * w + x) * 4
                if pixels[i] < 235 || pixels[i + 1] < 235 || pixels[i + 2] < 235 {
                    minX = min(minX, x); maxX = max(maxX, x)
                }
            }
        }
        XCTAssertNotEqual(minX, Int.max, "no bookmark drawn in the trailing \(slot) points")
        return (CGFloat(w - maxX) / scale)...(CGFloat(w - minX) / scale)
    }

    func testTheBookmarkStaysPutWhenTheRecipeIsSaved() throws {
        let outlined = try inkExtent(
            render(LibraryRow(recipe: summary(saved: false), onClick: {}, onSaveToList: {})),
            slot: LibraryRow.bookmarkSlot
        )
        let filled = try inkExtent(
            render(LibraryRow(recipe: summary(saved: true), onClick: {}, onSaveToList: {})),
            slot: LibraryRow.bookmarkSlot
        )
        XCTAssertEqual(outlined.lowerBound, filled.lowerBound, accuracy: 1, "the glyph's trailing edge moved")
        XCTAssertEqual(outlined.upperBound, filled.upperBound, accuracy: 1, "the glyph's leading edge moved")
        // And it sits inside the slot, not flush against the row's edge as a bare glyph would.
        XCTAssertGreaterThan(filled.lowerBound, 8, "the filled bookmark is flush with the row's edge")
    }
}
