import Foundation

/// Tallies for one approach on one task: what the product rule cares about is `confidentWrong`,
/// an answer shown to the user that is wrong. `abstained` keeps today's as-written behaviour.
struct Tally {
    var correct = 0, confidentWrong = 0, abstained = 0, rejected = 0, total = 0
    var seconds: [Double] = []

    mutating func add(_ outcome: Outcome, seconds s: Double? = nil) {
        total += 1
        switch outcome {
        case .correct: correct += 1
        case .wrong: confidentWrong += 1
        case .abstain: abstained += 1
        }
        if let s { seconds.append(s) }
    }

    static func pct(_ n: Int, _ d: Int) -> String { d == 0 ? "–" : String(format: "%.0f%%", 100.0 * Double(n) / Double(d)) }

    var medianMs: String {
        guard !seconds.isEmpty else { return "–" }
        let sorted = seconds.sorted()
        let ms = sorted[sorted.count / 2] * 1000
        return ms < 1 ? "<1 ms" : String(format: "%.0f ms", ms)
    }

    /// | approach | n | correct | confident wrong | abstain | checker rejected | median latency |
    func row(_ name: String) -> String {
        "| \(name) | \(total) | \(correct) (\(Tally.pct(correct, total))) | \(confidentWrong) (\(Tally.pct(confidentWrong, total))) "
            + "| \(abstained) (\(Tally.pct(abstained, total))) | \(rejected) | \(medianMs) |"
    }

    static let header = "| Approach | n | Correct | Confident wrong | Abstained (as written) | Checker rejected | Median latency |\n|---|---:|---:|---:|---:|---:|---:|"
}

enum Outcome { case correct, wrong, abstain }

enum Norm {
    static func text(_ s: String?) -> String? {
        guard var t = s?.lowercased() else { return nil }
        for (a, b) in [("’", "'"), ("‘", "'"), ("™", ""), ("®", ""), ("*", ""), ("-", " ")] { t = t.replacingOccurrences(of: a, with: b) }
        t = t.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
        t = t.trimmingCharacters(in: CharacterSet.punctuationCharacters.union(.whitespaces))
        return t.isEmpty ? nil : t
    }

    /// A name is right when it holds the gold name as whole words and adds at most two words.
    static func nameMatches(_ out: String?, _ gold: String?) -> Bool {
        guard let g = text(gold) else { return text(out) == nil }
        guard let o = text(out) else { return false }
        let padded = " \(o) "
        return padded.contains(" \(g) ") && o.split(separator: " ").count <= g.split(separator: " ").count + 2
    }

    static let units: [String: String] = [
        "cups": "cup", "c": "cup", "c.": "cup", "tablespoon": "tbsp", "tablespoons": "tbsp", "tbsp.": "tbsp",
        "tbs": "tbsp", "teaspoon": "tsp", "teaspoons": "tsp", "tsp.": "tsp", "ounce": "oz", "ounces": "oz", "oz.": "oz",
        "pound": "lb", "pounds": "lb", "lbs": "lb", "lb.": "lb", "lbs.": "lb", "gram": "g", "grams": "g", "gr": "g",
        "kilogram": "kg", "kilograms": "kg", "milliliter": "ml", "milliliters": "ml", "millilitre": "ml", "millilitres": "ml",
        "liter": "l", "liters": "l", "litre": "l", "litres": "l", "fl. oz.": "fl oz", "fl oz.": "fl oz",
        "fluid ounce": "fl oz", "fluid ounces": "fl oz", "sticks": "stick", "cans": "can", "cloves": "clove",
        "packages": "package", "pinches": "pinch", "bunches": "bunch", "quarts": "quart", "tins": "tin",
        "packets": "packet", "handfuls": "handful", "pouches": "pouch", "sachets": "sachet",
    ]

    static func unit(_ s: String?) -> String? {
        guard let t = s?.lowercased().trimmingCharacters(in: .whitespaces), !t.isEmpty, t != "null" else { return nil }
        return units[t] ?? units[t.trimmingCharacters(in: CharacterSet(charactersIn: "."))] ?? t.trimmingCharacters(in: CharacterSet(charactersIn: "."))
    }
}
