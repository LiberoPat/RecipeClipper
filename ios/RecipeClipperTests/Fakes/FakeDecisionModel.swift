import Combine
import Foundation
@testable import RecipeClipper

/// The decision model, faked (#104): `reply` answers each prompt (nil: "can't right now"), by
/// default `answer` with confidence "high". `asked` records every prompt.
final class FakeDecisionModel: DecisionModel {
    var languages: Set<String>
    var answer: String
    var reply: ((DecisionPrompt) -> DecisionReply?)?
    private(set) var asked: [DecisionPrompt] = []

    init(languages: Set<String> = ["en", "de", "fr"], answer: String = "unsure") {
        self.languages = languages
        self.answer = answer
    }

    func supports(language: String) async -> Bool { languages.contains(language) }

    func ask(_ prompt: DecisionPrompt) async -> DecisionReply? {
        asked.append(prompt)
        if let reply { return reply(prompt) }
        return DecisionReply(answer: answer, confidence: "high")
    }
}

/// `DecisionRepository` in memory: `answers` are what the model would decide; `decide` moves
/// each asked question's answer into what `observe` emits. `asked` records the questions.
///
/// `decide` runs off the main actor, and a ViewModel can call it from two tasks at once (Groceries
/// asks an aisle and an ingredient name together), so each call is one step under a lock, as the
/// real repository's writes are on its serial database queue. Unlocked, one call's cache could be
/// replaced by the other's older copy, losing an answer the ViewModel then never asks for again.
final class FakeDecisionRepository: DecisionRepository {
    let answers: [DecisionQuestion: String]
    var asked: [DecisionQuestion] { lock.withLock { recorded } }
    private var recorded: [DecisionQuestion] = []
    private let lock = NSLock()
    private let subject = CurrentValueSubject<Decisions, Never>(.none)

    init(_ answers: [DecisionQuestion: String] = [:]) { self.answers = answers }

    func observe() -> AnyPublisher<Decisions, Never> { subject.eraseToAnyPublisher() }

    func current() async -> Decisions { subject.value }

    func decide(_ questions: [DecisionQuestion]) async {
        // Sent inside the lock too, so a later cache is never overtaken by an earlier one.
        lock.withLock {
            recorded += questions
            var cached = subject.value.answers
            for q in questions { if let a = answers[q] { cached[q] = a } }
            subject.send(Decisions(answers: cached))
        }
    }
}
