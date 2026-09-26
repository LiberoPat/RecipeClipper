import Foundation

/// A CSS selector from the small subset `shared/tables/site-rules.json` uses (#120): a tag,
/// `.class`, `#id`, `[attr]`, `[attr=v]`, `[attr^=v]` and `[attr*=v]` (the value optionally
/// quoted), compounded (`h3.heading`, `h3[class*=SubHed-]`), in comma lists. No combinators: a
/// card's parts are only ever looked for inside its list. Values are case-sensitive, and an
/// attribute's runs of whitespace count as one space. Anything else is a mistake in the table,
/// so the initializer fails. A port of Android's `CardSelector`, pinned to it by the
/// differential corpus.
struct CardSelector {

    /// `.x` is `class ~= x` (one of its words), `#x` is `id = x`; an empty operator only needs the attribute.
    private struct Condition { let attribute: String; let op: String; let value: String }

    private struct Compound { let tag: String?; let conditions: [Condition] }

    private let alternatives: [Compound]

    /// Text each alternative needs in the page's source ("" when it needs none), so a page
    /// without any can be passed over before its tree is built.
    let needles: [String]

    private static let tagPattern = JRegex("^[a-z][a-z0-9]*")
    private static let conditionPattern = JRegex(
        #"\.([A-Za-z0-9_-]+)|#([A-Za-z0-9_-]+)|\[([a-z0-9_-]+)(?:([*^]?=)(?:"([^"]*)"|'([^']*)'|([^\]"']+)))?\]"#
    )

    init?(_ text: String) {
        var alternatives: [Compound] = []
        for part in text.split(separator: ",", omittingEmptySubsequences: false) {
            guard let compound = Self.compound(part.trimmingCharacters(in: .whitespaces)) else { return nil }
            alternatives.append(compound)
        }
        self.alternatives = alternatives
        needles = alternatives.map { $0.conditions.first(where: { $0.op != "" })?.value ?? "" }
    }

    func matches(_ e: HtmlTree.Element) -> Bool {
        alternatives.contains { c in
            (c.tag == nil || c.tag == e.name) && c.conditions.allSatisfy { holds($0, e) }
        }
    }

    /// `root` and everything inside it that matches, in document order.
    func select(_ tree: HtmlTree, in root: Int) -> [Int] {
        CardIngredients.within(tree, root, matches)
    }

    private func holds(_ c: Condition, _ e: HtmlTree.Element) -> Bool {
        guard let raw = e.attributes[c.attribute] else { return false }
        let value = raw.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        switch c.op {
        case "=": return value == c.value
        case "^=": return value.hasPrefix(c.value)
        case "*=": return value.contains(c.value)
        case "~=": return value.split(separator: " ").contains { $0 == c.value }
        default: return true
        }
    }

    private static func compound(_ text: String) -> Compound? {
        let length = (text as NSString).length
        let tag = tagPattern.find(text).map(\.value)
        var at = tag.map { ($0 as NSString).length } ?? 0
        var conditions: [Condition] = []
        while at < length, let m = conditionPattern.find(text, from: at), m.start == at {
            if !m[1].isEmpty {
                conditions.append(Condition(attribute: "class", op: "~=", value: m[1]))
            } else if !m[2].isEmpty {
                conditions.append(Condition(attribute: "id", op: "=", value: m[2]))
            } else {
                conditions.append(Condition(attribute: m[3], op: m[4], value: m[5] + m[6] + m[7]))
            }
            at = m.end
        }
        guard at == length, tag != nil || !conditions.isEmpty else { return nil }
        return Compound(tag: tag, conditions: conditions)
    }
}
