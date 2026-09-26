import Foundation

/// Task 3: the #104 decisions on gold/decisions.jsonl. Rules (today), the generative model
/// through DecisionRule (two asks, both definite, same and "high"), and JevK5's option
/// probabilities above a threshold. Gold "a|b" accepts either; gold "unsure" means no definite
/// answer is right, so any definite answer counts as confident wrong.
enum DecisionsTask {
    static let thresholds = [0.8, 0.9, 0.95]

    static func outcome(_ answer: String?, _ gold: String) -> Outcome {
        guard let answer, answer != DecisionKind.unsure else { return .abstain }
        return gold.split(separator: "|").contains(Substring(answer)) ? .correct : .wrong
    }

    static func run(_ goldPath: String, llm: Bool) -> String {
        let rows = (try! String(contentsOfFile: goldPath, encoding: .utf8)).split(separator: "\n")
            .compactMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String] }
        var out = ""
        for kind in DecisionKind.allCases {
            var rules = Tally(), model = Tally(), jev = thresholds.map { _ in Tally() }
            var asked = 0, log: [String] = []
            for row in rows where row[0] == kind.rawValue {
                let lang = row[1], gold = row.last!, words = LanguageWords.forTag(lang) ?? .english
                let question: DecisionQuestion, ruleAnswer: String?, appAsks: Bool
                switch kind {
                case .countBracket:
                    question = .countBracket(row[2], language: lang)
                    ruleAnswer = nil // today: the line stays as written
                    appAsks = IngredientScaler.needsCountDecision(row[2], words: words)
                case .sameIngredient:
                    question = .sameIngredient(row[2], row[3], language: lang)
                    ruleAnswer = IngredientName.matches(row[2], row[3], words: words) ? "same" : nil // no match: Buy, today
                    appAsks = ruleAnswer == nil && DecisionCandidates.close(row[2], row[3], words: words)
                case .aisle:
                    question = .aisle(row[2], language: lang)
                    let a = Aisles.ofName(row[2], words: words)
                    ruleAnswer = a == .other ? nil : a.rawValue
                    appAsks = a == .other
                }
                if appAsks { asked += 1 }
                rules.add(outcome(ruleAnswer, gold), seconds: 0)
                guard llm else { continue }
                var replies: [DecisionReply] = [], seconds = 0.0
                for n in 0..<DecisionRule.asks {
                    let prompt = DecisionPrompts.prompt(question, n)
                    let schema: [String: Any] = [
                        "type": "object", "required": ["answer", "confidence"],
                        "properties": ["answer": ["type": "string", "enum": prompt.options],
                                       "confidence": ["type": "string", "enum": ["high", "medium", "low"]]],
                    ]
                    let reply = Llm.chat(system: prompt.instructions, user: prompt.text, json: schema)
                    seconds += reply.seconds
                    let o = Llm.object(reply.text)
                    replies.append(DecisionReply(answer: o?["answer"] as? String ?? "", confidence: o?["confidence"] as? String ?? ""))
                }
                let judged = DecisionRule.judge(kind, replies)
                model.add(outcome(judged, gold), seconds: seconds)
                let prompt = DecisionPrompts.prompt(question, 0)
                let asking = prompt.instructions.components(separatedBy: "\nAnswer with one of").first ?? prompt.instructions
                var jevLog = "-"
                if let j = Llm.jev(state: prompt.text, question: asking, options: kind.options) {
                    let best = j.probs.indices.max { j.probs[$0] < j.probs[$1] }!
                    for (i, t) in thresholds.enumerated() {
                        jev[i].add(outcome(j.probs[best] >= t ? kind.options[best] : nil, gold), seconds: j.seconds)
                    }
                    jevLog = String(format: "%@ %.2f", kind.options[best], j.probs[best])
                }
                log.append("\(row.dropFirst(2).dropLast().joined(separator: " / "))\tgold=\(gold)\trules=\(ruleAnswer ?? "-")\tllm=\(judged) \(replies.map { "\($0.answer):\($0.confidence)" })\tjev=\(jevLog)\tapp asks=\(appAsks)")
            }
            try? log.joined(separator: "\n").write(toFile: "results/decisions-\(kind.rawValue).tsv", atomically: true, encoding: .utf8)
            out += "### Decision: \(kind.rawValue) (n = \(rules.total); the app would ask the model about \(asked))\n\n\(Tally.header)\n"
            out += rules.row("Rules (today)") + "\n"
            if llm {
                out += model.row("Generative LLM + DecisionRule") + "\n"
                for (i, t) in thresholds.enumerated() { out += jev[i].row("JevK5-4B, p ≥ \(t)") + "\n" }
            }
            out += "\n"
        }
        return out
    }
}
