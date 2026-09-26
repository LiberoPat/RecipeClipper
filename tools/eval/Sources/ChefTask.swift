import Foundation

/// Task 4: Chef mode. Each gold recipe's steps (English, at most 8 a recipe, long enough to send),
/// shortened with the iOS instructions, then ShortStepCheck with the recipe's ingredient lines.
/// Every reply goes to results/chef.tsv. gold/chef.jsonl is the replies read by hand (#129), with
/// gold/chef-recipes.jsonl their recipes' ingredient lines; --no-llm replays those replies through
/// the check, so a change to the check is measured on the same text. Replies the gold doesn't
/// have yet land in results/chef-unlabelled.jsonl, labelled "?", to read and add.
enum ChefTask {
    static let instructions = """
        You shorten one step of a recipe for someone cooking it right now. Reply with the short \
        step only, in the same language as the step. Keep every number, amount, time, temperature \
        and unit exactly as written. Add nothing that isn't in the step, and don't number it.
        """

    private struct Item { let url: String; let step: String; let short: String?; let seconds: Double? }

    /// A gold row: [url, step, short, "faithful" | "unfaithful", note]. Faithful: a cook reading
    /// only the short step does the same thing (every action, ingredient, piece of equipment and
    /// cue kept, nothing added).
    private static func key(_ step: String, _ short: String) -> String {
        ShortStepCheck.tidy(step) + "\t" + ShortStepCheck.tidy(short)
    }

    private static func rows(_ path: String) -> [[Any]] {
        ((try? String(contentsOfFile: path, encoding: .utf8)) ?? "").split(separator: "\n")
            .compactMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [Any] }
    }

    private static func line(_ row: [Any]) -> String {
        String(data: try! JSONSerialization.data(withJSONObject: row, options: [.withoutEscapingSlashes]), encoding: .utf8)!
    }

    static func run(_ dir: String, llm: Bool) -> String {
        var labels: [String: Bool] = [:]
        var items: [Item] = []
        for row in rows("gold/chef.jsonl") {
            guard let url = row[0] as? String, let step = row[1] as? String, let short = row[2] as? String,
                  let label = row[3] as? String else { continue }
            labels[key(step, short)] = label == "faithful"
            if !llm { items.append(Item(url: url, step: step, short: short, seconds: nil)) }
        }
        var recipes: [String: [String]] = [:]
        for row in rows("gold/chef-recipes.jsonl") {
            if let url = row[0] as? String, let lines = row[1] as? [String] { recipes[url] = lines }
        }
        if llm {
            var pages: [String] = []
            for page in EvalPage.load(dir) where (page.gold.language ?? "en").hasPrefix("en") {
                let steps = page.gold.instructions.filter(ShortStepCheck.worthShortening).prefix(8)
                if steps.isEmpty { continue }
                recipes[page.url] = page.gold.ingredients
                pages.append(line([page.url, page.gold.ingredients]))
                for step in steps {
                    let reply = Llm.chat(system: instructions, user: step)
                    let short = reply.text.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    items.append(Item(url: page.url, step: step, short: short, seconds: reply.seconds))
                }
            }
            try? pages.joined(separator: "\n").write(toFile: "results/chef-recipes.jsonl", atomically: true, encoding: .utf8)
        }

        var shown = 0, faithfulShown = 0, unfaithfulShown = 0, faithfulRejected = 0, unlabelled = 0
        var failedLength = 0, failedNumbers = 0, failedTimes = 0, failedWords = 0
        var saved: [Double] = [], log: [String] = [], unread: [String] = []
        for item in items {
            let lines = recipes[item.url] ?? []
            let candidate = ShortStepCheck.tidy(item.short ?? "")
            let shorter = !candidate.isEmpty && candidate.utf16.count < ShortStepCheck.tidy(item.step).utf16.count
            let label = shorter ? labels[key(item.step, candidate)] : nil
            if shorter && label == nil {
                unlabelled += 1
                unread.append(line([item.url, item.step, candidate, "?", ""]))
            }
            let verdict: String
            if ShortStepCheck.accept(item.step, item.short, words: .english, ingredients: lines) != nil {
                shown += 1
                if label == true { faithfulShown += 1 } else if label == false { unfaithfulShown += 1 }
                saved.append(1 - Double(candidate.utf16.count) / Double(ShortStepCheck.tidy(item.step).utf16.count))
                verdict = "OK"
            } else {
                if label == true { faithfulRejected += 1 }
                let reason: String
                if !shorter {
                    failedLength += 1; reason = "not shorter"
                } else if !ShortStepCheck.numbers(candidate).isSubset(of: ShortStepCheck.numbers(item.step)) {
                    failedNumbers += 1; reason = "a number not in the step"
                } else if !ShortStepCheck.keepsTimes(item.step, candidate, words: .english) {
                    failedTimes += 1; reason = "a time or temperature changed or dropped"
                } else {
                    failedWords += 1; reason = "a word dropped or added"
                }
                verdict = "NO (\(reason))"
            }
            let read = label.map { $0 ? "faithful" : "unfaithful" } ?? (shorter ? "?" : "-")
            log.append("\(verdict)\t\(read)\t\(item.step)\t\(item.short ?? "-")")
        }
        try? log.joined(separator: "\n").write(toFile: "results/chef.tsv", atomically: true, encoding: .utf8)
        try? unread.joined(separator: "\n").write(toFile: "results/chef-unlabelled.jsonl", atomically: true, encoding: .utf8)
        let n = items.count
        let meanSaved = saved.isEmpty ? 0 : 100 * saved.reduce(0, +) / Double(saved.count)
        let seconds = items.compactMap(\.seconds).sorted()
        let median = seconds.isEmpty ? "–" : String(format: "%.1f s", seconds[seconds.count / 2])
        let source = llm ? "\(Llm.model) now" : "the gold replies"
        return """
            ### Chef mode: short steps (n = \(n) steps, replies from \(source))

            | Approach | Short version shown | Faithful among shown | Unfaithful shown | Faithful rejected | Rejected: not shorter | Rejected: new number | Rejected: time/temperature | Rejected: words | Mean length cut (shown) | Median latency |
            |---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
            | Today (as written) | 0 | – | – | – | – | – | – | – | 0% | – |
            | LLM + ShortStepCheck | \(shown) (\(Tally.pct(shown, n))) | \(faithfulShown) (\(Tally.pct(faithfulShown, shown))) | \(unfaithfulShown) | \(faithfulRejected) | \(failedLength) | \(failedNumbers) | \(failedTimes) | \(failedWords) | \(String(format: "%.0f%%", meanSaved)) | \(median) |

            Shorter replies not in gold/chef.jsonl (see results/chef-unlabelled.jsonl): \(unlabelled).

            """
    }
}
