import XCTest
@testable import RecipeClipper

/// The decision cache (#104) against real SQLite: asked twice, judged, cached, asked once.
@MainActor
final class DecisionRepositoryTests: XCTestCase {
    private let line = "4 Apfel (ca. 800g)"
    private var question: DecisionQuestion { .countBracket(line, language: "de") }

    /// These tests ask count brackets, which also need their own flag since #127.
    private func make(
        _ model: FakeDecisionModel, db: AppDatabase? = nil, on: @escaping () -> Bool = { true },
        brackets: @escaping () -> Bool = { true }
    ) throws -> DefaultDecisionRepository {
        DefaultDecisionRepository(
            db: try db ?? AppDatabase(path: nil), model: model, clock: DataTestClock(), isOn: on, countBracketsOn: brackets
        )
    }

    func testWithCountBracketsOffNoneIsAskedAndACachedOneChangesNothing() async throws {
        let model = FakeDecisionModel(answer: "total")
        let db = try AppDatabase(path: nil)
        var brackets = false
        let repository = try make(model, db: db, brackets: { brackets })
        await repository.decide([question])
        XCTAssertTrue(model.asked.isEmpty)
        try await db.write { try AiDecisionDao(db: $0).insert(self.question, answer: "each", now: 1) }
        let off = await repository.current()
        XCTAssertNil(off.countBracket(line, language: "de"))
        await repository.decide([.aisle("miso paste", language: "en")])
        XCTAssertEqual(model.asked.count, 2)
        brackets = true
        let on = await repository.current()
        XCTAssertEqual(on.countBracket(line, language: "de"), .each)
    }

    func testADefiniteAnswerIsAskedTwiceCachedAndNeverAskedAgain() async throws {
        let model = FakeDecisionModel(answer: "total")
        let repository = try make(model)
        await repository.decide([question])
        XCTAssertEqual(model.asked.map(\.options), [["total", "each", "unsure"], ["each", "total", "unsure"]])
        let decided = await repository.current()
        XCTAssertEqual(decided.countBracket(line, language: "de"), .total)
        await repository.decide([question])
        XCTAssertEqual(model.asked.count, 2)
    }

    func testADisagreementIsCachedAsUnsureAndChangesNothing() async throws {
        let model = FakeDecisionModel()
        var n = 0
        model.reply = { _ in n += 1; return DecisionReply(answer: n == 1 ? "total" : "each", confidence: "high") }
        let repository = try make(model)
        await repository.decide([question])
        await repository.decide([question])
        XCTAssertEqual(model.asked.count, 2)
        let decided = await repository.current()
        XCTAssertNil(decided.countBracket(line, language: "de"))
        XCTAssertEqual(decided.answers[question], "unsure")
    }

    func testAModelThatCantAnswerNowCachesNothing() async throws {
        let model = FakeDecisionModel(answer: "total")
        model.reply = { _ in nil }
        let repository = try make(model)
        await repository.decide([question])
        let none = await repository.current()
        XCTAssertTrue(none.answers.isEmpty)
        model.reply = nil
        await repository.decide([question])
        let decided = await repository.current()
        XCTAssertEqual(decided.countBracket(line, language: "de"), .total)
    }

    func testFlagOffOrAnUnsupportedLanguageAsksNothing() async throws {
        let model = FakeDecisionModel(answer: "total")
        var on = true
        let repository = try make(model, on: { on })
        await repository.decide([.countBracket("1 lata (397 g)", language: "pt")])
        XCTAssertTrue(model.asked.isEmpty)
        await repository.decide([question])
        on = false
        let off = await repository.current()
        XCTAssertEqual(off, .none)
        await repository.decide([.countBracket("3 large apples (about 3 cups)", language: "en")])
        XCTAssertEqual(model.asked.count, 2)
    }

    /// A user_version 11 file, built by the real migrations, gains `ai_decisions`.
    func testAVersion11DatabaseMigratesToVersion12() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try AppDatabase.migrate(old, upTo: 11)
            try RecipeDao(db: old).insert(dataRecipeRecord("https://example.com/cake", viewedAt: 1))
        }
        let db = try AppDatabase(path: path)
        let version = try await db.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        XCTAssertEqual(version, 12)
        try await db.write { try AiDecisionDao(db: $0).insert(self.question, answer: "total", now: 1) }
        let answer = try await db.read { try AiDecisionDao(db: $0).answer(self.question) }
        XCTAssertEqual(answer, "total")
        let kept = try await db.read { try RecipeDao(db: $0).findByUrl("https://example.com/cake") }
        XCTAssertNotNil(kept)
    }
}
