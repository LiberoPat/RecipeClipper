import XCTest
@testable import RecipeClipper

/// `OrderedWrites` on its own (#234; Android's OrderedWritesTest): the recipe screen's one queue
/// for cook progress and servings.
@MainActor
final class OrderedWritesTests: XCTestCase {

    private final class Log {
        var entries: [String] = []
    }

    func testWritesLandOneAtATimeInTheOrderTheyWereMade() async {
        let writes = OrderedWrites()
        let log = Log()

        writes.enqueue {
            log.entries.append("slow start")
            for _ in 0 ..< 50 { await Task.yield() }
            log.entries.append("slow end")
        }
        writes.enqueue {
            log.entries.append("quick start")
            log.entries.append("quick end")
        }
        await writes.settle()

        XCTAssertEqual(log.entries, ["slow start", "slow end", "quick start", "quick end"])
    }

    func testAWriteMadeWhileTheQueueDrainsGoesBehindIt() async {
        let writes = OrderedWrites()
        let log = Log()

        writes.enqueue {
            for _ in 0 ..< 50 { await Task.yield() }
            log.entries.append("first")
        }
        await Task.yield()
        writes.enqueue { log.entries.append("second") }
        await writes.settle()

        XCTAssertEqual(log.entries, ["first", "second"])
    }

    func testQueuedWritesStillLandOnceTheQueueIsGone() async {
        let repository = FakeRecipeRepository()
        var writes: OrderedWrites? = OrderedWrites()

        writes?.enqueue { await repository.setServingsTarget(id: 3, target: 6) }
        writes?.enqueue { await repository.setServingsTarget(id: 3, target: nil) }
        writes = nil // the screen was popped
        await settleMain()

        XCTAssertEqual(repository.setServingsTargetCalls.map(\.target), [6, nil])
    }
}
