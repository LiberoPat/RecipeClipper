import Combine
import Foundation

/// The cache of the on-device model's typed decisions (#104), Android's `AiDecisionDao`.
struct AiDecisionDao {
    let db: SQLiteConnection

    func all() throws -> [DecisionQuestion: String] {
        let rows = try db.query("SELECT kind, input, language, answer FROM ai_decisions") {
            ($0.string(0), $0.string(1), $0.string(2), $0.string(3))
        }
        var out: [DecisionQuestion: String] = [:]
        for (kind, input, language, answer) in rows {
            guard let kind = DecisionKind(rawValue: kind) else { continue }
            out[DecisionQuestion(kind: kind, input: input, language: language)] = answer
        }
        return out
    }

    func answer(_ q: DecisionQuestion) throws -> String? {
        try db.queryOne(
            "SELECT answer FROM ai_decisions WHERE kind = ? AND input = ? AND language = ?",
            q.kind.rawValue, q.input, q.language
        ) { $0.string(0) }
    }

    /// OR IGNORE: a question already answered keeps its row (and its uid).
    func insert(_ q: DecisionQuestion, answer: String, now: Int64) throws {
        try db.run(
            "INSERT OR IGNORE INTO ai_decisions (kind, input, language, answer, updatedAt, uid) VALUES (?, ?, ?, ?, ?, ?)",
            q.kind.rawValue, q.input, q.language, answer, now, newUid()
        )
    }
}

final class DefaultDecisionRepository: DecisionRepository {
    private let db: AppDatabase
    private let model: DecisionModel
    private let isOn: () -> Bool
    /// Count brackets need their own flag too (#127): off, a cached answer is never applied.
    private let countBracketsOn: () -> Bool
    private let clock: Clock

    init(
        db: AppDatabase, model: DecisionModel, clock: Clock, isOn: @escaping () -> Bool,
        countBracketsOn: @escaping () -> Bool = { false }
    ) {
        self.db = db
        self.model = model
        self.clock = clock
        self.isOn = isOn
        self.countBracketsOn = countBracketsOn
    }

    func observe() -> AnyPublisher<Decisions, Never> {
        let isOn = self.isOn, brackets = countBracketsOn
        return db.observe { conn in isOn() ? try Self.load(conn, brackets: brackets()) : .none }
    }

    func current() async -> Decisions {
        guard isOn() else { return .none }
        let brackets = countBracketsOn()
        return (try? await db.read { try Self.load($0, brackets: brackets) }) ?? .none
    }

    private static func load(_ conn: SQLiteConnection, brackets: Bool) throws -> Decisions {
        Decisions(answers: try AiDecisionDao(db: conn).all().filter { brackets || $0.key.kind != .countBracket })
    }

    func decide(_ questions: [DecisionQuestion]) async {
        guard isOn() else { return }
        let brackets = countBracketsOn()
        var seen = Set<DecisionQuestion>()
        for q in questions where (brackets || q.kind != .countBracket) && seen.insert(q).inserted {
            if Task.isCancelled { return }
            // One at a time; a question two screens ask at once costs a second ask, never a second row.
            await ask(q)
        }
    }

    private func ask(_ q: DecisionQuestion) async {
        let cached = try? await db.read { try AiDecisionDao(db: $0).answer(q) }
        if cached != nil { return }
        guard await model.supports(language: q.language) else { return }
        var replies: [DecisionReply] = []
        for n in 0..<DecisionRule.asks {
            guard let reply = await model.ask(DecisionPrompts.prompt(q, n)), !Task.isCancelled else { return }
            replies.append(reply)
        }
        let answer = DecisionRule.judge(q.kind, replies)
        let now = clock.now()
        do {
            try await db.write { try AiDecisionDao(db: $0).insert(q, answer: answer, now: now) }
        } catch {
            dataLog.error("decision save failed: \(String(describing: error), privacy: .public)")
        }
    }
}
