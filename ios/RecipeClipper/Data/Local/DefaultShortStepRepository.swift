import Combine
import CryptoKit
import Foundation

/// Chef mode's cache of short steps (#100), Android's `ShortStepDao`. Run inside `AppDatabase`.
struct ShortStepDao {
    let db: SQLiteConnection

    /// Step hash → short text (nil: failed the check) for `recipeId`, written in `language`.
    func rows(recipeId: Int64, language: String) throws -> [String: String?] {
        let rows = try db.query(
            "SELECT stepHash, shortText FROM short_steps WHERE recipeId = ? AND language = ?",
            recipeId, language
        ) { ($0.string(0), $0.optionalString(1)) }
        return Dictionary(rows, uniquingKeysWith: { first, _ in first })
    }

    /// OR IGNORE: a step already written keeps its row (and its uid).
    func insert(recipeId: Int64, stepHash: String, language: String, shortText: String?, now: Int64) throws {
        try db.run(
            """
            INSERT OR IGNORE INTO short_steps (recipeId, stepHash, language, shortText, updatedAt, uid)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            recipeId, stepHash, language, shortText, now, newUid()
        )
    }

    /// Drops `recipeId`'s rows for steps it no longer has, or written in another language.
    func prune(recipeId: Int64, language: String, keep: [String]) throws {
        let marks = keep.isEmpty ? "''" : keep.map { _ in "?" }.joined(separator: ", ")
        let args: [SQLiteBindable?] = [recipeId, language] + keep.map { $0 as SQLiteBindable? }
        try db.run(
            "DELETE FROM short_steps WHERE recipeId = ? AND (language != ? OR stepHash NOT IN (\(marks)))",
            arguments: args
        )
    }
}

final class DefaultShortStepRepository: ShortStepRepository {
    private let db: AppDatabase
    private let shortener: StepShortener
    private let clock: Clock

    init(db: AppDatabase, shortener: StepShortener, clock: Clock) {
        self.db = db
        self.shortener = shortener
        self.clock = clock
    }

    func support() async -> ChefSupport { await shortener.support() }

    func observe(_ recipe: Recipe) -> AnyPublisher<[String?], Never> {
        let none = recipe.instructions.map { _ in String?.none }
        guard let language = LanguageWords.forRecipe(recipe)?.language else { return Just(none).eraseToAnyPublisher() }
        let id = recipe.id
        return db.observe { conn in
            let byHash = try ShortStepDao(db: conn).rows(recipeId: id, language: language)
            return recipe.instructions.map { byHash[Self.stepHash($0)] ?? nil }
        }
    }

    func fill(_ recipe: Recipe) async {
        guard let words = LanguageWords.forRecipe(recipe) else { return }
        let language = words.language
        let id = recipe.id
        let hashes = recipe.instructions.map(Self.stepHash)
        let done: Set<String>
        do {
            done = try await db.write { conn in
                let dao = ShortStepDao(db: conn)
                try dao.prune(recipeId: id, language: language, keep: hashes)
                return Set(try dao.rows(recipeId: id, language: language).keys)
            }
        } catch {
            dataLog.error("shortSteps prune failed: \(String(describing: error), privacy: .public)")
            return
        }
        var written = done
        for (index, step) in recipe.instructions.enumerated() {
            if Task.isCancelled { return }
            let hash = hashes[index]
            if written.contains(hash) || !ShortStepCheck.worthShortening(step) { continue }
            guard let short = await shortener.shorten(step, language: language), !Task.isCancelled else { continue }
            let accepted = ShortStepCheck.accept(step, short, words: words)
            let now = clock.now()
            do {
                try await db.write { conn in
                    try ShortStepDao(db: conn).insert(
                        recipeId: id, stepHash: hash, language: language, shortText: accepted, now: now
                    )
                }
            } catch {
                dataLog.error("shortSteps save failed: \(String(describing: error), privacy: .public)")
            }
            written.insert(hash)
        }
    }

    /// The step's text as stored, as SHA-256 hex: what a cached short step is keyed by.
    static func stepHash(_ step: String) -> String {
        SHA256.hash(data: Data(step.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
