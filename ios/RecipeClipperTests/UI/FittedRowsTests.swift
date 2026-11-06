import SwiftUI
import XCTest
@testable import RecipeClipper

/// The home-screen widgets' list (#309) laid out for real (#329): the rows that fit the height
/// at the text size in use, then how many more. The row count used to assume fixed heights, so
/// at large Dynamic Type sizes, or in a small widget, rows and "+N more" were clipped.
@MainActor
final class FittedRowsTests: XCTestCase {
    private final class Seen {
        var rows: [String] = []
        var more: Int?
        var beside = false
    }

    private struct Laid {
        let rows: [String]
        let more: Int?
        /// The count sits beside the one row, not under the rows.
        let beside: Bool
        /// How high what shows is: the title, the rows and the count.
        let height: CGFloat
    }

    /// The inside of a small widget on the smallest iPhone: 148 points, less its margins.
    private let small = CGSize(width: 116, height: 116)

    /// A widget's list as `HomeWidgetCard` builds it, each row one line or (`meals`) two.
    private func list(
        _ lines: [String], _ size: DynamicTypeSize, meals: Bool, seen: Seen? = nil, appeared: XCTestExpectation? = nil
    ) -> some View {
        FittedRows(rows: lines) {
            Text("Groceries").font(.custom("Fraunces-Regular", size: 17)).lineLimit(1)
        } row: { line in
            VStack(alignment: .leading, spacing: 0) {
                if meals { Text("Dinner").font(.custom("Karla-Regular", size: 12)) }
                Text(line).font(.custom("Karla-Regular", size: 15))
            }
            .onAppear {
                seen?.rows.append(line)
                appeared?.fulfill()
            }
        } more: { count, beside in
            Text(beside ? "+\(count)" : "+\(count) more").font(.custom("Karla-Regular", size: 14)).onAppear {
                seen?.more = count
                seen?.beside = beside
            }
        }
        .environment(\.dynamicTypeSize, size)
    }

    /// Puts `count` lines on screen in `space`, and reports what showed.
    private func layOut(_ count: Int, in space: CGSize, size: DynamicTypeSize = .large, meals: Bool = false) async -> Laid {
        let lines = (1...count).map { "item \($0)" }
        let seen = Seen()
        let appeared = expectation(description: "the rows appear")
        appeared.assertForOverFulfill = false
        let shown = list(lines, size, meals: meals, seen: seen, appeared: appeared)
            .frame(width: space.width, height: space.height, alignment: .topLeading)

        let frame = CGRect(origin: .zero, size: space)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map(UIWindow.init(windowScene:)) ?? UIWindow(frame: frame)
        window.frame = frame
        window.rootViewController = UIHostingController(rootView: shown)
        window.isHidden = false
        window.layoutIfNeeded()
        await fulfillment(of: [appeared], timeout: 10)
        window.isHidden = true

        // The same list, asked how much of that space it takes.
        let taken = UIHostingController(rootView: list(lines, size, meals: meals)).sizeThatFits(in: space)
        XCTAssertEqual(seen.rows.count, Set(seen.rows).count, "one cut of the list is on screen, not several")
        // Rows appear in no promised order: listed here as the list has them.
        return Laid(rows: lines.filter(seen.rows.contains), more: seen.more, beside: seen.beside, height: taken.height)
    }

    func testAListThatFitsShowsWholeWithNoCount() async {
        let laid = await layOut(3, in: CGSize(width: 116, height: 300))
        XCTAssertEqual(laid.rows, ["item 1", "item 2", "item 3"])
        XCTAssertNil(laid.more)
    }

    func testALongListShowsTheRowsThatFitThenHowManyMore() async {
        let laid = await layOut(20, in: small)
        XCTAssertGreaterThanOrEqual(laid.rows.count, 2)
        XCTAssertEqual(laid.rows, (1...laid.rows.count).map { "item \($0)" })
        XCTAssertEqual(laid.more, 20 - laid.rows.count)
        XCTAssertFalse(laid.beside)
        XCTAssertLessThanOrEqual(laid.height, small.height, "nothing is clipped")
    }

    func testLargerTextShowsFewerRowsNeverClippedOnes() async {
        let usual = await layOut(20, in: small)
        for size in [DynamicTypeSize.xxxLarge, .accessibility2, .accessibility5] {
            let large = await layOut(20, in: small, size: size)
            XCTAssertLessThan(large.rows.count, usual.rows.count, "\(size)")
            XCTAssertEqual(large.rows.first, "item 1", "\(size): an item is always named")
            XCTAssertEqual(large.more, 20 - large.rows.count, "\(size): and the count is the rest")
            XCTAssertLessThanOrEqual(large.height, small.height, "\(size): nothing is clipped")
        }
    }

    // A meal is two lines, and at the largest sizes one of them and the count are higher than a
    // small widget: the text steps down only as far as it takes, rather than a meal going unnamed.
    func testEvenTheLargestTextNamesAMealAndShowsTheCount() async {
        for space in [small, CGSize(width: 300, height: 116)] {
            for size in [DynamicTypeSize.accessibility1, .accessibility3, .accessibility5] {
                let laid = await layOut(4, in: space, size: size, meals: true)
                XCTAssertEqual(laid.rows, ["item 1"], "\(size) in \(space)")
                XCTAssertEqual(laid.more, 3, "\(size) in \(space)")
                XCTAssertFalse(laid.beside, "\(size) in \(space): the count has its own line")
                XCTAssertLessThanOrEqual(laid.height, space.height, "\(size) in \(space): nothing is clipped")
            }
        }
    }

    func testEveryHeightShowsWhatFitsIt() async {
        var last = 0
        for height in stride(from: CGFloat(60), through: 300, by: 60) {
            let laid = await layOut(20, in: CGSize(width: 116, height: height))
            XCTAssertGreaterThanOrEqual(laid.rows.count, max(last, 1), "\(height)")
            XCTAssertEqual(laid.more, 20 - laid.rows.count, "\(height)")
            XCTAssertLessThanOrEqual(laid.height, height, "\(height): nothing is clipped")
            last = laid.rows.count
        }
    }

    // With no room for two rows, the count goes beside the first item, not in its place.
    func testWithRoomForOneRowTheFirstItemIsNamedWithTheCountBesideIt() async {
        let laid = await layOut(20, in: CGSize(width: 116, height: 50))
        XCTAssertEqual(laid.rows, ["item 1"])
        XCTAssertEqual(laid.more, 19)
        XCTAssertTrue(laid.beside)
        XCTAssertLessThanOrEqual(laid.height, 50, "nothing is clipped")
    }
}