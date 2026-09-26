import Foundation

/// Which questions are worth asking the model (#104): only where today's rules give up.
/// Android's `DecisionCandidates`, pinned by the corpus's `Count` and `Close` rows.
enum DecisionCandidates {

    /// A count bracket per line that stays as written only because of one.
    static func countBrackets(_ lines: [String], words: LanguageWords?) -> [DecisionQuestion] {
        guard let words else { return [] }
        return lines.filter { IngredientScaler.needsCountDecision($0, words: words) }
            .map { DecisionQuestion.countBracket($0, language: words.language) }
    }

    /// True when `a` and `b` share a word of three or more letters, or one's last word ends with
    /// the other's, but `IngredientName.matches` says no. Never without spaces between words.
    static func close(_ a: String, _ b: String, words: LanguageWords) -> Bool {
        if !words.spaced || IngredientName.matches(a, b, words: words) { return false }
        let x = wordsOf(IngredientDensities.headPhrase(a, words: words))
        let y = wordsOf(IngredientDensities.headPhrase(b, words: words))
        if x.isEmpty || y.isEmpty || x == y { return false }
        if x.contains(where: { $0.count >= 3 && y.contains($0) }) { return true }
        let (lx, ly) = (x[x.count - 1], y[y.count - 1])
        return (lx.count > ly.count && ly.count >= 3 && lx.hasSuffix(ly))
            || (ly.count > lx.count && lx.count >= 3 && ly.hasSuffix(lx))
    }

    private static func wordsOf(_ text: String) -> [String] {
        text.lowercased().split(whereSeparator: { $0 == " " || $0 == "-" })
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: ",.;")) }
            .filter { !$0.isEmpty }
    }

    /// For each of `names` no pantry item matches, a question per close item that would make it Have.
    static func samePairs(_ names: [String], language: String?, pantry: [PantryItem]) -> [DecisionQuestion] {
        guard let words = LanguageWords.forTag(language) else { return [] }
        let candidates = pantry.filter { $0.language == words.language && ($0.inStock || $0.alwaysHave) }
        var seen = Set<String>()
        return names.filter { seen.insert($0).inserted }
            .filter { PantryMatch.find($0, language: words.language, pantry: pantry) == nil }
            .flatMap { name in
                candidates.filter { close(name, $0.name, words: words) }
                    .map { DecisionQuestion.sameIngredient(name, $0.name, language: words.language) }
            }
    }

    /// The aisle question for `line`, when it has a name and the keyword table puts it in Other.
    static func aisle(_ line: String, language: String?) -> DecisionQuestion? {
        guard let words = LanguageWords.forTag(language), let name = IngredientName.of(line, words: words),
              Aisles.ofName(name, words: words) == .other else { return nil }
        return .aisle(name, language: words.language)
    }
}
