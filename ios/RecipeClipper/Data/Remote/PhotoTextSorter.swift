import Foundation

/// One line of text the device read from a photo (#198), in reading order, with the
/// recogniser's `confidence` (0 to 1) when it gives one. Android's `PhotoLine`.
struct PhotoLine: Equatable, Sendable {
    let text: String
    var confidence: Float? = nil
}

/// What `PhotoTextSorter.sort` made of a photo's lines: the recipe editor's content, before the
/// cook checks it. When `sorted` is false the splitter found no recipe, and `ingredients` holds
/// every line read, as read, for the cook to sort by hand.
struct PhotoReading: Equatable {
    let ingredients: [String]
    let instructions: [String]
    var yield: String? = nil
    var prepTime: String? = nil
    var cookTime: String? = nil
    var totalTime: String? = nil
    let sorted: Bool
    /// The lines shown that came from text the recogniser was unsure of: "check this".
    var uncertain: [String] = []

    /// Nothing at all was read.
    var isEmpty: Bool { ingredients.isEmpty && instructions.isEmpty }
}

/// Sorts the lines read from a photo (#198) with the same splitter that reads a typed Reddit
/// post (`RecipeTextSplitter`), so headers, lists and numbered steps are recognised the same way.
///
/// **Nothing is invented or corrected.** Every line is the recogniser's text (only trimmed, and
/// cleaned by the splitter as a typed post's lines are); an amount it misread stays misread, and
/// the lines it was unsure of are named in `uncertain` for the cook to check against the photo.
/// When the splitter finds no recipe the lines are returned unsorted, never guessed at.
///
/// Pure: lines in, data out. A line-for-line port of Android's `PhotoTextSorter.kt`.
enum PhotoTextSorter {
    /// Below this, a line is marked "check this". Vision's accurate recogniser answers 0.3 for
    /// text it is unsure of and 0.5 or 1 otherwise; ML Kit's scores fall on the same scale.
    static let lowConfidence: Float = 0.5

    private static let minPart = 4

    static func sort(_ lines: [PhotoLine]) -> PhotoReading {
        let read = lines.map { PhotoLine(text: $0.text.kTrimmed, confidence: $0.confidence) }.filter { !$0.text.isEmpty }
        let unsure = read.filter { ($0.confidence ?? 1) < lowConfidence }.map(\.text)
        let split = read.isEmpty ? nil : RecipeTextSplitter.split(read.map(\.text).joined(separator: "\n"))
        guard let split, !(split.ingredients.isEmpty && split.instructions.isEmpty) else {
            let all = read.map(\.text)
            return PhotoReading(ingredients: all, instructions: [], sorted: false, uncertain: marked(all, unsure: unsure))
        }
        return PhotoReading(
            ingredients: split.ingredients,
            instructions: split.instructions,
            yield: split.yield,
            prepTime: split.prepTime,
            cookTime: split.cookTime,
            totalTime: split.totalTime,
            sorted: true,
            uncertain: marked(split.ingredients + split.instructions, unsure: unsure)
        )
    }

    /// The `shown` lines that hold, or are held in, a line the recogniser was unsure of. The
    /// splitter only takes markup off a line ("1." or "•"), so a line shown still contains the
    /// text it came from. A piece shorter than four characters ("1", "Mix") is matched only as
    /// a whole line, so it can't mark every line it happens to appear in.
    static func marked(_ shown: [String], unsure: [String]) -> [String] {
        guard !unsure.isEmpty else { return [] }
        var out: [String] = []
        for line in shown where !out.contains(line) {
            let hit = unsure.contains { u in
                line == u
                    || (u.utf16.count >= minPart && line.contains(u))
                    || (line.utf16.count >= minPart && u.contains(line))
            }
            if hit { out.append(line) }
        }
        return out
    }
}
