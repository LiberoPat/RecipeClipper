import Foundation

/// Chef mode's gate (#100): whether a short version of a step, written by the on-device model,
/// may be shown in place of the step as written. Pure, and the same as Android's (the
/// differential corpus's `Short` rows pin it).
///
/// The model writes words; code owns every number. A short version passes only if it is shorter
/// than the step, every number in it (fractions, decimal commas and each end of a range, as
/// written) appears in the step, it states exactly the step's times and temperatures ("350°F
/// (180°C)" may keep either half), and it keeps the words a cook acts on and adds none
/// (`keepsWords`, #129). Anything else, and any recipe language the app has no words for, shows
/// the step as written: a doubtful short step is rejected, never shown.
enum ShortStepCheck {

    /// `short`, tidied, when it may stand for `original`; nil to show `original` as written.
    /// `ingredients` are the recipe's lines, whose names the short step must keep.
    static func accept(_ original: String, _ short: String?, words: LanguageWords?, ingredients: [String] = []) -> String? {
        guard let words, let short else { return nil }
        let candidate = tidy(short)
        // UTF-16 lengths, as Kotlin's String.length.
        if candidate.isEmpty || candidate.utf16.count >= tidy(original).utf16.count { return nil }
        if !numbers(candidate).isSubset(of: numbers(original)) { return nil }
        if !keepsTimes(original, candidate, words: words) { return nil }
        if !keepsWords(original, candidate, words: words, ingredients: ingredients) { return nil }
        return candidate
    }

    /// True when `short` states exactly `original`'s times and temperatures: none changed, none
    /// added, none dropped ("350°F (180°C)" may keep either half).
    static func keepsTimes(_ original: String, _ short: String, words: LanguageWords) -> Bool {
        if Set(StepTimers.durations(original, words: words)) != Set(StepTimers.durations(short, words: words)) {
            return false
        }
        let stated = TemperatureConverter.temperatures(original, words: words)
        let kept = Set(TemperatureConverter.temperatures(short, words: words).flatMap { $0 })
        return kept.isSubset(of: Set(stated.flatMap { $0 })) && stated.allSatisfy { $0.contains { kept.contains($0) } }
    }

    /// True when `short` drops nothing a cook acts on and adds nothing (#129), by the words of
    /// shared/tables/<language>/chef.json. `original` words that must stay: an ingredient named
    /// in `ingredients` (the words of its `IngredientName`, or of the line when it has none), an
    /// action, a piece of equipment, a qualifier ("not", "if", "until") and a time word ("a few
    /// minutes"). `short` may add only function words. A word counts as the same through an
    /// ending ("whisking", "whisk"), an abbreviation ("temp") or its unit ("min", "minutes").
    /// A language written without spaces compares characters: every kanji and katakana of
    /// `short` is in `original`, and each table entry and ingredient name is in both or neither.
    static func keepsWords(_ original: String, _ short: String, words: LanguageWords, ingredients: [String] = []) -> Bool {
        let w = words.compiled(Words.self, Words.init)
        if !words.spaced { return keepsCharacters(original, short, words, w, ingredients) }
        let said = tokens(original)
        let kept = tokens(short)
        let saidForms = Set(said.flatMap { forms($0, words, w) })
        let keptForms = Set(kept.flatMap { forms($0, words, w) })
        for token in kept where token.utf16.count >= 2 && !w.functionWords.contains(token) {
            if !forms(token, words, w).contains(where: { saidForms.contains($0) }) { return false }
        }
        let named = ingredientForms(ingredients, words, w)
        for (i, token) in said.enumerated() where token.utf16.count >= 2 {
            let f = forms(token, words, w)
            // "the rest of the flour": a noun, not the action.
            let action = f.contains { w.actions.contains($0) } && !(i > 0 && w.notAfter.contains(said[i - 1]))
            let needed = action || f.contains { w.kept.contains($0) || named.contains($0) || $0.hasPrefix(time) }
            if needed && !f.contains(where: { keptForms.contains($0) }) { return false }
        }
        return true
    }

    /// A step this short ("Serve warm.") is left as written, never sent to the model.
    static func worthShortening(_ step: String) -> Bool { tidy(step).utf16.count >= minLength }

    static let minLength = 40

    /// One line: trimmed, whitespace collapsed, a leading bullet and wrapping quotes removed.
    static func tidy(_ text: String) -> String {
        var s = spaces.replace(text.kTrimmed, with: " ")
        s = bullet.replace(s, with: "")
        if s.utf16.count >= 2, let first = s.first, let last = s.last, quotes.contains(first), quotes.contains(last) {
            s = String(s.dropFirst().dropLast()).kTrimmed
        }
        return s
    }

    /// Every number in `text`, as written: "1,5", "1 1/2", "1½", "½"; a range's ends separately.
    static func numbers(_ text: String) -> Set<String> {
        var plain = ""
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0xFF10...0xFF19: plain.unicodeScalars.append(Unicode.Scalar(scalar.value - 0xFF10 + 0x30)!)
            case 0xFF0F, 0x2044: plain.append("/")
            default: plain.unicodeScalars.append(scalar)
            }
        }
        return Set(number.findAll(plain).map { spacedFraction.replace(spaces.replace($0.value, with: " ")) { "\($0[1])\($0[2])" } })
    }

    private static func keepsCharacters(
        _ original: String, _ short: String, _ words: LanguageWords, _ w: Words, _ ingredients: [String]
    ) -> Bool {
        let said = Set(letter.findAll(original).map(\.value))
        let hiragana: ClosedRange<UInt16> = 0x3040...0x309F
        if letter.findAll(short).contains(where: { !said.contains($0.value) && !hiragana.contains($0.value.utf16.first!) }) {
            return false
        }
        if w.entries.contains(where: { has(original, $0) != has(short, $0) }) { return false }
        return !ingredients.compactMap { IngredientName.of($0, words: words) }.contains { has(original, $0) && !has(short, $0) }
    }

    /// Kotlin's `String.contains`: a literal UTF-16 search.
    private static func has(_ text: String, _ part: String) -> Bool {
        (text as NSString).range(of: part, options: .literal).location != NSNotFound
    }

    /// The words of `text`, lowercase, with an apostrophe inside one kept ("don't", "l'eau").
    private static func tokens(_ text: String) -> [String] {
        word.findAll(text).map { $0.value.lowercased().replacingOccurrences(of: "’", with: "'") }
    }

    /// Every form `token` matches by: `wordForms`, plus its time ("#t60") or unit ("#ucup").
    private static func forms(_ token: String, _ words: LanguageWords, _ w: Words) -> Set<String> {
        var out = wordForms(token, w.endings, w.abbreviations)
        if let seconds = StepTimers.unitSeconds(token, words: words) { out.insert(time + String(seconds)) }
        if w.unit.matchEntire(token) != nil, let unit = MeasureUnit.fromText(token, words: words) { out.insert("#u\(unit)") }
        return out
    }

    /// `token`, its long form, the part after an elision ("l'eau": "eau"), and its bases.
    fileprivate static func wordForms(_ token: String, _ endings: [(String, String)], _ abbreviations: [String: String]) -> Set<String> {
        var out: Set<String> = [token]
        if let long = abbreviations[token] { out.insert(long) }
        let units = Array(token.utf16)
        if let apostrophe = units.firstIndex(of: 0x27), units.count - apostrophe - 1 >= 3 {
            out.insert(token.u16Substring(from: apostrophe + 1))
        }
        for (ending, replacement) in endings {
            let tail = Array(ending.utf16)
            guard units.count >= tail.count, Array(units[(units.count - tail.count)...]) == tail else { continue }
            let length = units.count - tail.count
            if length < 2 { continue }
            let base = token.u16Substring(0, length)
            out.insert(base + replacement)
            // "stirring", "chopped": the doubled last letter goes too.
            if replacement.isEmpty && length >= 3 && units[length - 1] == units[length - 2] {
                out.insert(token.u16Substring(0, length - 1))
            }
        }
        return out
    }

    /// The forms of every word of `lines`' ingredient names that isn't a size, modifier or unit.
    private static func ingredientForms(_ lines: [String], _ words: LanguageWords, _ w: Words) -> Set<String> {
        var out = Set<String>()
        for line in lines {
            if line.kIsBlank || line.kTrimmed.hasSuffix(":") { continue }
            for token in tokens(IngredientName.of(line, words: words) ?? line) {
                if token.utf16.count < 3 || w.functionWords.contains(token) || w.notIngredient.contains(token) { continue }
                let f = forms(token, words, w)
                if !f.contains(where: { $0.hasPrefix("#") }) { out.formUnion(f) }
            }
        }
        return out
    }

    /// One language's chef.json, plus the names table's words that don't name an ingredient.
    private final class Words {
        let endings: [(String, String)]
        let abbreviations: [String: String]
        let actions: Set<String>
        let kept: Set<String>
        let entries: [String]
        let notAfter: Set<String>
        let functionWords: Set<String>
        let notIngredient: Set<String>
        let unit: JRegex

        init(_ words: LanguageWords) {
            let table = words.table("chef")
            func pairs(_ key: String) -> [(String, String)] { (table[key] as? [[String]] ?? []).map { ($0[0], $0[1]) } }
            let endings = pairs("endings")
            let abbreviations = Dictionary(pairs("abbreviations"), uniquingKeysWith: { _, last in last })
            func forms(_ key: String) -> Set<String> {
                Set(words.strings("chef", key).flatMap { ShortStepCheck.wordForms($0, endings, abbreviations) })
            }
            self.endings = endings
            self.abbreviations = abbreviations
            actions = forms("actions")
            kept = forms("equipment").union(forms("qualifiers"))
            entries = ["actions", "equipment", "qualifiers"].flatMap { words.strings("chef", $0) }
            notAfter = Set(words.strings("chef", "notAfter"))
            functionWords = Set(words.strings("chef", "functionWords"))
            notIngredient = Set(["leadingWords", "trailingWords", "matchModifiers", "cutPhrases", "conjunctions"].flatMap { key in
                words.strings("names", key).flatMap { $0.split(separator: " ").map(String.init) }
            })
            unit = JRegex(UnitPatterns.of(words).plain, ignoreCase: true)
        }
    }

    private static let time = "#t"
    private static let word = JRegex(#"\p{L}+(?:['’]\p{L}+)*"#)
    private static let letter = JRegex(#"\p{L}"#)
    private static let fractions = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅐⅛⅜⅝⅞⅑⅒"
    private static let number = JRegex(#"\d+(?:[.,]\d+)*(?:\s+\d+/\d+|/\d+|\s*["# + fractions + #"])?|["# + fractions + "]")
    private static let spacedFraction = JRegex(#"(\d) (["# + fractions + "])")
    private static let spaces = JRegex(#"\s+"#)
    private static let bullet = JRegex(#"^[-•*]\s+"#)
    private static let quotes: Set<Character> = ["\"", "'", "“", "”", "„", "«", "»", "「", "」"]
}
