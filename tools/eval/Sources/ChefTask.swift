import Foundation

/// Task 4: Chef mode. Each gold recipe's steps (English, at most 8 a recipe, long enough to send),
/// shortened with the iOS instructions, then ShortStepCheck. Accepted pairs go to results/chef.tsv
/// for a hand review of meaning, which no checker can judge.
enum ChefTask {
    static let instructions = """
        You shorten one step of a recipe for someone cooking it right now. Reply with the short \
        step only, in the same language as the step. Keep every number, amount, time, temperature \
        and unit exactly as written. Add nothing that isn't in the step, and don't number it.
        """

    static func run(_ dir: String, llm: Bool) -> String {
        var steps: [String] = []
        for page in EvalPage.load(dir) where (page.gold.language ?? "en").hasPrefix("en") {
            steps += page.gold.instructions.filter(ShortStepCheck.worthShortening).prefix(8)
        }
        guard llm else { return "### Chef mode: \(steps.count) steps would be sent\n" }
        var accepted = 0, failedLength = 0, failedNumbers = 0, failedOther = 0
        var saved: [Double] = [], seconds: [Double] = [], log: [String] = []
        for step in steps {
            let reply = Llm.chat(system: instructions, user: step)
            seconds.append(reply.seconds)
            let short = reply.text.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            if let kept = ShortStepCheck.accept(step, short, words: .english) {
                accepted += 1
                saved.append(1 - Double(kept.utf16.count) / Double(ShortStepCheck.tidy(step).utf16.count))
                log.append("OK\t\(step)\t\(kept)")
                continue
            }
            let candidate = ShortStepCheck.tidy(short ?? "")
            let reason: String
            if candidate.isEmpty || candidate.utf16.count >= ShortStepCheck.tidy(step).utf16.count {
                failedLength += 1; reason = "not shorter"
            } else if !ShortStepCheck.numbers(candidate).isSubset(of: ShortStepCheck.numbers(step)) {
                failedNumbers += 1; reason = "a number not in the step"
            } else {
                failedOther += 1; reason = "a time or temperature changed or dropped"
            }
            log.append("NO (\(reason))\t\(step)\t\(short ?? "-")")
        }
        try? log.joined(separator: "\n").write(toFile: "results/chef.tsv", atomically: true, encoding: .utf8)
        let n = steps.count
        let meanSaved = saved.isEmpty ? 0 : 100 * saved.reduce(0, +) / Double(saved.count)
        let median = seconds.isEmpty ? "–" : String(format: "%.1f s", seconds.sorted()[seconds.count / 2])
        return """
            ### Chef mode: short steps (n = \(n) steps from the gold recipes)

            | Approach | Steps shorter on screen | Rejected: not shorter | Rejected: new number | Rejected: time/temperature | Mean length cut (accepted) | Median latency |
            |---|---:|---:|---:|---:|---:|---:|
            | Today (as written) | 0 | – | – | – | 0% | – |
            | LLM + ShortStepCheck | \(accepted) (\(Tally.pct(accepted, n))) | \(failedLength) | \(failedNumbers) | \(failedOther) | \(String(format: "%.0f%%", meanSaved)) | \(median) |

            """
    }
}
