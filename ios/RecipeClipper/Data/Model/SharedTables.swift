import Foundation

/// The word and density tables both apps share, from `shared/tables/` at the repository root
/// (#9). The folder is copied into the app bundle as `tables/` (see `project.yml`); Android
/// packages the same files as Java resources, so a table is edited once for both.
///
/// A missing or malformed table is a build mistake, not a runtime condition, so it traps.
enum SharedTables {

    typealias Table = [String: Any]

    /// A language's table, e.g. `"units"` for `tables/en/units.json`. See `LanguageWords`.
    static func load(_ name: String, _ language: String) -> Table { read("\(language)/\(name)") }

    /// A table that no language changes, e.g. `"url"` for `tables/url.json`.
    static func read(_ path: String) -> Table {
        guard let url = Bundle.main.url(forResource: "tables/\(path)", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let table = try? JSONSerialization.jsonObject(with: data) as? Table
        else { fatalError("Missing or malformed shared table tables/\(path).json") }
        return table
    }

    static func strings(_ table: Table, _ key: String) -> [String] { table[key] as? [String] ?? [] }

    static func objects(_ table: Table, _ key: String) -> [Table] { table[key] as? [Table] ?? [] }

    /// Regex fragments joined as one non-capturing alternation. No fragments never matches: an
    /// empty alternation would match everywhere.
    static func alternation(_ fragments: [String]) -> String {
        fragments.isEmpty ? "(?!)" : "(?:" + fragments.joined(separator: "|") + ")"
    }
}
