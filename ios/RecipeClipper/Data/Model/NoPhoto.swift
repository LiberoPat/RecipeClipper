import Foundation

/// What a recipe's image is drawn from when it has no photo (#403): its stable `uid` (the tint),
/// its `title` (the monogram), its stored `language` and its ingredient `ingredients` lines (the
/// label). Never a stock image: only the recipe's own words.
struct NoPhotoSource: Equatable {
    let uid: String
    let title: String
    var language: String? = nil
    var ingredients: [String] = []
}

/// What `NoPhoto.art` picked: the monogram's letter, the label's names, and the tint.
struct NoPhotoArt: Equatable {
    let initial: String?
    let labels: [String]
    let tint: Int
}

/// The words and colour of a recipe's designed image when it has no photo (#403): the title's
/// first letter, skipping a leading article from `shared/tables/<language>/articles.json`; the
/// first ingredient names as written, without amount or unit (read by `IngredientName`),
/// skipping headings and the language's staples (`staples.json`: salt, pepper, oil, water); and
/// one of `tints` warm tints from a stable hash of the recipe's uid, so a recipe keeps its colour
/// everywhere and after a rename. A port of the Kotlin `NoPhoto`: the hash is FNV-1a, the same on
/// both, so a recipe has one colour on both platforms.
enum NoPhoto {

    /// Paprika, saffron, sage, cocoa and rose.
    static let tints = 5

    private final class Articles {
        // Longest first, so "un'" is tried before "un"; each as unicode scalars.
        let spaced: [[Unicode.Scalar]]
        let glued: [[Unicode.Scalar]]

        init(_ words: LanguageWords) {
            let all = words.strings("articles", "articles").map { Array($0.lowercased().unicodeScalars) }
                .enumerated().sorted { $0.element.count != $1.element.count ? $0.element.count > $1.element.count : $0.offset < $1.offset }
                .map(\.element)
            glued = all.filter { $0.last == "'" || $0.last == "\u{2019}" }
            spaced = all.filter { article in !glued.contains(where: { $0 == article }) }
        }
    }

    private final class Staples {
        let keys: Set<String>

        init(_ words: LanguageWords) {
            keys = Set(words.strings("staples", "staples").map {
                IngredientName.key(IngredientDensities.headPhrase($0, words: words), words: words)
            })
        }
    }

    /// The title's first letter, uppercased, after a leading article of the recipe's language;
    /// nil when the title has no letter at all. An article that would leave no letter stays
    /// ("The" is T). The title's words only: nothing is translated.
    static func initial(_ title: String, language: String?) -> String? {
        let words = LanguageWords.forRecipe(language: language, title: title, lines: [])
        var points = Array(title.kTrimmed.unicodeScalars)
        if let words {
            let articles = words.compiled(Articles.self) { Articles($0) }
            let rest = withoutArticle(points, articles)
            if rest.contains(where: isLetter) { points = rest }
        }
        guard let first = points.first(where: isLetter) else { return nil }
        let letter = String(first)
        let upper = letter.uppercased()
        return upper.unicodeScalars.count == 1 ? upper : letter
    }

    private static func withoutArticle(_ points: [Unicode.Scalar], _ articles: Articles) -> [Unicode.Scalar] {
        for article in articles.glued
        where points.count > article.count && lower(Array(points.prefix(article.count))) == article {
            return Array(points.dropFirst(article.count))
        }
        for article in articles.spaced
        where points.count > article.count && lower(Array(points.prefix(article.count))) == article
            && isWhitespace(points[article.count]) {
            return Array(points.dropFirst(article.count).drop(while: isWhitespace))
        }
        return points
    }

    private static func lower(_ points: [Unicode.Scalar]) -> [Unicode.Scalar] {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: points)
        return Array(String(view).lowercased().unicodeScalars)
    }

    private static func isLetter(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: true
        default: false
        }
    }

    // Java's Character.isWhitespace: a space separator that isn't no-break, or a line/paragraph
    // separator, or one of the control whitespace characters.
    private static func isWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09...0x0D, 0x1C...0x1F: true
        case 0xA0, 0x2007, 0x202F: false
        default:
            switch scalar.properties.generalCategory {
            case .spaceSeparator, .lineSeparator, .paragraphSeparator: true
            default: false
            }
        }
    }

    /// Up to `max` ingredient names, in the recipe's order: each line's name as written (its own
    /// capitals), without amount, unit or preparation. A heading, a line `IngredientName` can't
    /// name ("salt and pepper", "juice of 1 lemon"), a staple and a name already taken are
    /// skipped. Empty when none is left (or `language` has no words): the caller shows the
    /// monogram.
    static func labels(_ ingredients: [String], language: String?, max: Int) -> [String] {
        guard max > 0, let words = LanguageWords.forRecipe(language: language, title: "", lines: ingredients) else { return [] }
        let staples = words.compiled(Staples.self) { Staples($0) }.keys
        var taken = Set<String>()
        var labels: [String] = []
        for line in ingredients {
            if IngredientHeading.isHeading(line) { continue }
            guard let name = IngredientName.of(line, words: words) else { continue }
            let key = IngredientName.key(name, words: words)
            if staples.contains(key) || !taken.insert(key).inserted { continue }
            labels.append(written(line, name))
            if labels.count == max { break }
        }
        return labels
    }

    // The name as the line writes it ("Zucker", "d'agneau", "all-purpose flour"): the name's
    // letters in the line, case aside, with an apostrophe the name dropped and a hyphen or a run
    // of spaces where it has a space. The name as read (lowercase) when the line doesn't hold it.
    private static func written(_ line: String, _ name: String) -> String {
        let text = Array(line.unicodeScalars)
        let wanted = Array(name.unicodeScalars)
        for start in text.indices {
            guard let end = matchAt(text, start, wanted) else { continue }
            var view = String.UnicodeScalarView()
            view.append(contentsOf: text[start..<end])
            return String(view)
        }
        return name
    }

    private static func matchAt(_ text: [Unicode.Scalar], _ start: Int, _ name: [Unicode.Scalar]) -> Int? {
        var at = start
        var j = 0
        while j < name.count {
            let c = name[j]
            if c == " " {
                if at >= text.count || !isSeparator(text[at]) { return nil }
                while at < text.count && isSeparator(text[at]) { at += 1 }
                j += 1
            } else if at < text.count && text[at].properties.lowercaseMapping == c.properties.lowercaseMapping {
                at += 1
                j += 1
            } else if at > start && at < text.count && (text[at] == "'" || text[at] == "\u{2019}") {
                at += 1
            } else {
                return nil
            }
        }
        return at
    }

    private static func isSeparator(_ scalar: Unicode.Scalar) -> Bool { isWhitespace(scalar) || scalar == "-" }

    /// One of `tints` from `uid`: FNV-1a over its UTF-8 bytes, modulo five. Never changes.
    static func tintIndex(_ uid: String) -> Int {
        var hash: UInt64 = 2166136261
        for byte in uid.utf8 {
            hash ^= UInt64(byte)
            hash = (hash &* 16777619) & 0xFFFF_FFFF
        }
        return Int(hash % UInt64(tints))
    }

    /// Everything the image draws: the monogram's letter, up to `maxLabels` names, the tint.
    static func art(_ source: NoPhotoSource, maxLabels: Int) -> NoPhotoArt {
        NoPhotoArt(
            initial: initial(source.title, language: source.language),
            labels: labels(source.ingredients, language: source.language, max: maxLabels),
            tint: tintIndex(source.uid)
        )
    }
}
