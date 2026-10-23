import Foundation

/// What was typed into the Pantry's "Add to the pantry" field (#302; Android's `PantryEntry`,
/// the rules there): the item's `name`, and its `quantity` when the text started with one, as
/// typed ("3", "2 cups", "1½ lb").
struct PantryEntry: Equatable {
    let name: String
    let quantity: String?

    /// `text`, trimmed, split into an amount and a name only when it starts with one real amount
    /// that `words` read: a positive number or fraction, then a unit the tables know or a space,
    /// then a name that starts with a letter ("3 onions", "2 cups rice", "500 g Mehl"). A
    /// container word belongs to the amount as a unit does ("2 cans tomatoes", amounts.json
    /// `containers`). A word joining the unit or container to the name ("2 cups of rice",
    /// amounts.json `unitLinks`) belongs to neither. Anything else stays whole as the name, so no number is guessed: "00 flour",
    /// "7up", "5-spice", "1/2", "2-3 onions", "2 eggs plus 3 yolks", "180 C", and every text in a
    /// language with no tables, or whose amounts follow the name.
    static func split(_ text: String, words: LanguageWords?) -> PantryEntry {
        let typed = text.kTrimmed
        let whole = PantryEntry(name: typed, quantity: nil)
        guard let words else { return whole }
        let p = IngredientScaler.patterns(words)
        if p.amountAfterName || p.unreadable(typed) { return whole }
        // "2 eggs plus 3 yolks", "1 cup butter or 1/2 cup oil": more than one amount.
        guard IngredientScaler.sides(p, typed)?.count == 1 else { return whole }
        guard let lead = p.leading.find(typed) else { return whole }
        if !lead[4].isEmpty { return whole } // a range is no one figure
        guard let value = p.parse(lead[2]), value > 0 else { return whole } // "00 flour"
        let afterNumber = lead.end
        let rest = typed.u16Substring(from: afterNumber)
        if p.notAnAmount.containsMatch(in: rest) || p.temperature(lead, rest) { return whole }
        let c = UnitConverter.patterns(words)
        var end = afterNumber
        var nameStart = afterNumber
        if let unit = p.unitAtStart.find(rest) {
            guard MeasureUnit.fromText(unit[1], words: words) != nil else { return whole }
            end += unit.end
            let after = typed.u16Substring(from: end)
            // "1 cup plus 2 tbsp flour": a second part.
            if c.continuationAtStart.containsMatch(in: after) { return whole }
            nameStart = end + (links(words).find(after)?.end ?? 0)
        } else if rest.first?.isWhitespace != true {
            return whole // "7up", "5-spice"
        } else if let container = p.containerFirst.find(rest) {
            // "2 cans tomatoes", "1 Dose Tomaten": a container (amounts.json `containers`)
            // belongs to the amount, as a unit does, so the name is just "tomatoes".
            end = afterNumber + container.end
            nameStart = end + (links(words).find(typed.u16Substring(from: end))?.end ?? 0)
        }
        let name = typed.u16Substring(from: nameStart).kTrimmed
        guard name.first?.isLetter == true, !name.contains(where: { digits.contains($0) || fractions.contains($0) }) else {
            return whole
        }
        guard GroceryCombiner.notesOnly(name, c) else { return whole }
        return PantryEntry(name: name, quantity: typed.u16Substring(0, end).kTrimmed)
    }

    private static let digits = Set("0123456789０１２３４５６７８９")
    private static let fractions = Set("¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞")

    private final class Links {
        let atStart: JRegex
        init(_ words: LanguageWords) {
            atStart = JRegex(#"^\s*"# + SharedTables.alternation(words.strings("amounts", "unitLinks")), ignoreCase: true)
        }
    }

    private static func links(_ words: LanguageWords) -> JRegex { words.compiled(Links.self, Links.init).atStart }
}
