import XCTest
@testable import RecipeClipper

/// The retry for an unreadable reply and the runaway detector (#273). Android's `PageReplyTest`.
final class PageReplyTests: XCTestCase {

    private let cake = PageSelection(name: "Cake", ingredients: ["2 cups flour"], steps: ["Bake it."])

    /// Answers each ask with the next reply, recording whether it was the retry.
    private final class Asks {
        private var replies: [PageReply]
        private(set) var retries: [Bool] = []
        init(_ replies: PageReply...) { self.replies = replies }
        func ask(_ retry: Bool) async -> PageReply { retries.append(retry); return replies.removeFirst() }
    }

    func testReadableReplyIsUsedWithNoRetry() async {
        let asks = Asks(.picked(cake))
        let picked = await PageReplyRetry.pick(asks.ask)
        XCTAssertEqual(picked, cake)
        XCTAssertEqual(asks.retries, [false])
    }

    func testUnreadableReplyIsRetriedOnce() async {
        let asks = Asks(.unreadable, .picked(cake))
        let picked = await PageReplyRetry.pick(asks.ask)
        XCTAssertEqual(picked, cake)
        XCTAssertEqual(asks.retries, [false, true])
    }

    func testSecondUnreadableReplyGivesUp() async {
        let asks = Asks(.unreadable, .unreadable, .picked(cake))
        let picked = await PageReplyRetry.pick(asks.ask)
        XCTAssertNil(picked)
        XCTAssertEqual(asks.retries, [false, true])
    }

    func testRetryThatFailsGivesUp() async {
        let asks = Asks(.unreadable, .failed)
        let picked = await PageReplyRetry.pick(asks.ask)
        XCTAssertNil(picked)
        XCTAssertEqual(asks.retries, [false, true])
    }

    func testFailureIsNeverRetried() async {
        let asks = Asks(.failed, .picked(cake))
        let picked = await PageReplyRetry.pick(asks.ask)
        XCTAssertNil(picked)
        XCTAssertEqual(asks.retries, [false])
    }

    func testReplyCutOffWhileRepeatingALineRunsAway() {
        let line = "    \"1/4 cup (65 ml) water\",\n"
        let start = "{\n  \"name\": \"Chow Mein\",\n  \"ingredients\": [\n"
        XCTAssertTrue(RunawayReply.isRunaway(start + String(repeating: line, count: 4) + "    \"1/4 cup (6"))
        XCTAssertFalse(RunawayReply.isRunaway(start + String(repeating: line, count: 3) + "    \"1/4 cup (6"))
    }

    func testTimePaddedWithProseRunsAway() {
        let reply = "{\"name\": \"Tots\", \"ingredients\": [\"1 egg\"], \"prepTime\": \"30 minutes"
            + String(repeating: " mins, 30 minutes", count: 20)
        XCTAssertTrue(RunawayReply.isRunaway(reply))
        XCTAssertFalse(RunawayReply.isRunaway("{\"name\": \"Tots\", \"prepTime\": \"1 hour 30 minutes, plus overnight soaking\"}"))
    }

    func testLongNameOrLineRunsAway() {
        XCTAssertTrue(RunawayReply.isRunaway(PageSelection(name: String(repeating: "x", count: 201), ingredients: [], steps: [])))
        XCTAssertTrue(RunawayReply.isRunaway(PageSelection(name: "Cake", ingredients: [], steps: [String(repeating: "y", count: 2_001)])))
        XCTAssertFalse(RunawayReply.isRunaway(PageSelection(
            name: String(repeating: "x", count: 200), ingredients: [], steps: [String(repeating: "y", count: 2_000)])))
    }

    func testLineThreeTimesRunningOrFourTimesApartIsARecipe() {
        XCTAssertFalse(RunawayReply.isRunaway(PageSelection(name: "Cake", ingredients: Array(repeating: "1 egg", count: 3), steps: [])))
        XCTAssertFalse(RunawayReply.isRunaway(PageSelection(
            name: "Cake", ingredients: ["1 egg", "salt", "1 egg", "salt", "1 egg", "salt", "1 egg"], steps: [])))
        XCTAssertTrue(RunawayReply.isRunaway(PageSelection(name: "Cake", ingredients: [], steps: Array(repeating: "Stir.", count: 4))))
    }

    func testScanReadsTheFieldsSoFarLeniently() {
        let reply = "```json\n{\"steps\": [\"Mix \\\"well\\\".\", \"Bake\\u00e9\"], \"name\": \"Cake\", "
            + "\"extra\": {\"name\": \"no\", \"ingredients\": [\"no\"]}, \"yield\": null, \"cookTime\": \"PT30M\", "
            + "\"ingredients\": [\"2 cups flour\", \"1 e"
        XCTAssertEqual(
            RunawayReply.scan(reply),
            PageSelection(name: "Cake", ingredients: ["2 cups flour", "1 e"], steps: ["Mix \"well\".", "Bakeé"], cookTime: "PT30M")
        )
        XCTAssertEqual(RunawayReply.scan(""), PageSelection(name: nil, ingredients: [], steps: []))
    }
}
