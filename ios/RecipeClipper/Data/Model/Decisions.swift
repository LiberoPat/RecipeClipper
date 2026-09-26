import Foundation

/// The typed decisions the on-device model makes (#104), each a pick from `options` or
/// `unsure` (Android's `DecisionKind`). `rawValue` is what the cache stores.
enum DecisionKind: String, CaseIterable {
    /// A count's bracket ("4 Apfel (ca. 800g)"): the total for all the items, or each one's size.
    case countBracket
    /// A recipe's ingredient and a pantry item with close names: the same thing, or not.
    case sameIngredient
    /// The aisle of an ingredient the keyword table puts in Other. "other" keeps it there.
    case aisle

    static let unsure = "unsure"

    var options: [String] {
        switch self {
        case .countBracket: return ["total", "each"]
        case .sameIngredient: return ["same", "different"]
        case .aisle: return Aisle.allCases.map(\.rawValue)
        }
    }
}

/// A definite answer about a count's bracket. Unsure is nil: the line stays as written.
enum CountBracket: Equatable { case total, each }

/// One question: its kind, its input normalised (so it is asked once) and the recipe's language.
struct DecisionQuestion: Hashable {
    let kind: DecisionKind
    let input: String
    let language: String

    static let pair = " | "
    private static let whitespace = JRegex(#"\s+"#)

    static func normalize(_ text: String) -> String {
        whitespace.replace(text.kTrimmed, with: " ").lowercased()
    }

    static func countBracket(_ line: String, language: String) -> DecisionQuestion {
        DecisionQuestion(kind: .countBracket, input: normalize(line), language: language)
    }

    /// Either order is the same question.
    static func sameIngredient(_ a: String, _ b: String, language: String) -> DecisionQuestion {
        DecisionQuestion(kind: .sameIngredient, input: [normalize(a), normalize(b)].sorted().joined(separator: pair), language: language)
    }

    static func aisle(_ name: String, language: String) -> DecisionQuestion {
        DecisionQuestion(kind: .aisle, input: normalize(name), language: language)
    }
}

/// Every answer cached so far, by question: what the screens apply. `.none` is today's behaviour.
struct Decisions: Equatable {
    let answers: [DecisionQuestion: String]

    static let none = Decisions(answers: [:])

    func countBracket(_ line: String, language: String?) -> CountBracket? {
        guard let language else { return nil }
        switch answers[.countBracket(line, language: language)] {
        case "total": return .total
        case "each": return .each
        default: return nil
        }
    }

    /// True only for a definite "same".
    func sameIngredient(_ a: String, _ b: String, language: String?) -> Bool {
        guard let language else { return false }
        return answers[.sameIngredient(a, b, language: language)] == "same"
    }

    /// A definite aisle other than Other, or nil.
    func aisle(_ name: String, language: String?) -> Aisle? {
        guard let language, let key = answers[.aisle(name, language: language)],
              let aisle = Aisle(rawValue: key), aisle != .other else { return nil }
        return aisle
    }

    func isAnswered(_ question: DecisionQuestion) -> Bool { answers[question] != nil }
}
