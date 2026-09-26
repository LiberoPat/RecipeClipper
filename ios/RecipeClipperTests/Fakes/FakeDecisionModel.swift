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
final class FakeDecisionRepository: DecisionRepository {
    let answers: [DecisionQuestion: String]
    private(set) var asked: [DecisionQuestion] = []
    private let subject = CurrentValueSubject<Decisions, Never>(.none)

    init(_ answers: [DecisionQuestion: String] = [:]) { self.answers = answers }

    func observe() -> AnyPublisher<Decisions, Never> { subject.eraseToAnyPublisher() }

    func current() async -> Decisions { subject.value }

    func decide(_ questions: [DecisionQuestion]) async {
        asked += questions
        var cached = subject.value.answers
        for q in questions { if let a = answers[q] { cached[q] = a } }
        subject.send(Decisions(answers: cached))
    }
}
