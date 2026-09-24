import Foundation

/// One language's parsing words (#14): the tables under `shared/tables/<language>/`, which
/// Android loads too. The recipe's language picks the table, never the phone's, and languages
/// are never merged: "C" is a cup in English and Celsius elsewhere, and mixed rules convert
/// wrongly.
///
/// Every parser takes one (English by default, so existing callers and the differential corpus
/// are unchanged). A nil table means a language the app has no words for: every line stays as
/// written, with no scaling, conversion, temperature rewrite, timer or servings stepper, since
/// English rules would read "2 bis 3" as "4 bis 3".
///
/// Adding a language is adding its folder of tables and its code to `shipped`.
final class LanguageWords: Equatable, @unchecked Sendable {

    /// One instance per language, so identity is equality.
    static func == (lhs: LanguageWords, rhs: LanguageWords) -> Bool { lhs === rhs }

    /// The primary language subtag, e.g. "en".
    let language: String

    private init(_ language: String) {
        self.language = language
        rangeWords = SharedTables.alternation(SharedTables.strings(SharedTables.load("ranges", language), "words"))
        detectWords = JRegex(
            #"(?<!\p{L})"# + SharedTables.alternation(SharedTables.strings(SharedTables.load("language", language), "detect")) +
                #"(?!\p{L})"#,
            ignoreCase: true
        )
    }

    func table(_ name: String) -> SharedTables.Table { SharedTables.load(name, language) }

    func strings(_ table: String, _ key: String) -> [String] { SharedTables.strings(self.table(table), key) }

    /// Words that join the ends of a range ("4 to 6"), as one alternation.
    let rangeWords: String

    private let detectWords: JRegex

    private let lock = NSLock()
    private var compiled: [ObjectIdentifier: Any] = [:]

    /// The patterns `owner` builds from these words, built once per language. Each parser keeps
    /// its own, so its regexes stay beside its logic. Built outside the lock, as Kotlin's
    /// `getOrPut` does: one parser's patterns build another's (UnitConverter's use
    /// IngredientScaler's), and a race only builds the same thing twice.
    func compiled<T>(_ owner: T.Type, _ build: (LanguageWords) -> T) -> T {
        let key = ObjectIdentifier(owner)
        lock.lock()
        let existing = compiled[key] as? T
        lock.unlock()
        if let existing { return existing }
        let built = build(self)
        lock.lock()
        defer { lock.unlock() }
        if let raced = compiled[key] as? T { return raced }
        compiled[key] = built
        return built
    }

    /// Languages with tables, in the order detection breaks no ties (it needs a clear lead).
    static let shipped = ["en"]

    private static let loaded: [String: LanguageWords] =
        Dictionary(uniqueKeysWithValues: shipped.map { ($0, LanguageWords($0)) })

    static var english: LanguageWords { loaded["en"]! }

    private static let tag = JRegex(#"[a-z]{2,3}(?:-[a-z0-9]{1,8})*"#)

    /// A language tag as stored: trimmed, lowercased, `_` as `-` ("en_US" is "en-us"). Nil for
    /// anything that isn't a tag ("English", "", "x").
    static func normalize(_ tag: String?) -> String? {
        guard let tag else { return nil }
        let s = tag.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().replacingOccurrences(of: "_", with: "-")
        return self.tag.matchEntire(s) != nil ? s : nil
    }

    /// The words for a stored tag, by its primary subtag; nil for a language with none.
    static func forTag(_ tag: String?) -> LanguageWords? {
        guard let normalized = normalize(tag) else { return nil }
        let primary = normalized.split(separator: "-", maxSplits: 1).first.map(String.init) ?? normalized
        return loaded[primary]
    }

    /// The shipped language whose words the text uses most, when clearly ahead: at least 3 hits
    /// and more than twice the runner-up's. Nil when nothing is clear.
    static func detect(_ text: String) -> String? {
        let scores = shipped.map { ($0, loaded[$0]!.detectWords.findAll(text).count) }
            .sorted { $0.1 > $1.1 } // stable in Swift 5: ties keep shipped order
        guard let (best, score) = scores.first else { return nil }
        let runnerUp = scores.count > 1 ? scores[1].1 : 0
        return score >= 3 && score > 2 * runnerUp ? best : nil
    }

    /// The recipe's language: the JSON-LD `inLanguage`, else the page's `<html lang>`, else
    /// detection from the recipe's own words, else English. Never the phone's locale: a Spanish
    /// speaker may share an English recipe.
    static func resolve(declared: String?, page: String?, text: () -> String) -> String {
        normalize(declared) ?? normalize(page) ?? detect(text()) ?? "en"
    }

    /// The text detection reads: the name and the ingredient lines.
    static func detectionText(name: String, ingredients: [String]) -> String {
        ([name] + ingredients).joined(separator: "\n")
    }

    /// The words a recipe is read with. A recipe stored before #14 has no language: detect it.
    static func forRecipe(_ recipe: Recipe) -> LanguageWords? {
        forTag(recipe.language ?? resolve(declared: nil, page: nil) {
            detectionText(name: recipe.name, ingredients: recipe.ingredients)
        })
    }
}
