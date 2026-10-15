import Foundation

/// Lines `first` to `last` of the numbered window, counted from 1, both included (#128).
struct LineRun: Equatable {
    var first: Int
    var last: Int
}

/// What the model picked, by line number (#128): the name, yield and times copied as the page
/// writes them (short), the ingredients and steps as runs of the numbered window's lines.
/// Android's `PagePick`.
struct PagePick: Equatable {
    var name: String?
    var ingredients: [LineRun]
    var steps: [LineRun]
    var yield: String? = nil
    var prepTime: String? = nil
    var cookTime: String? = nil
    var totalTime: String? = nil
}

/// The model names lines, never copies them (#128): the window goes to it with each line numbered
/// (`numbered`), it answers runs of line numbers, and `selection` takes those lines from the
/// window as written, so a long recipe's reply stays a few dozen tokens. Pure; Android's
/// `PageLines`, rule for rule, pinned by the differential corpus's `Lines` rows.
enum PageLines {
    /// Stripped from the start of a line: list bullets and the checkbox a recipe card prints.
    private static let bullets = Set("•◦▪▫‣⁃●○■□▢☐☑✓✔*·-–—".utf16)

    /// `window`'s lines, each after its number in brackets: "[1] Banana Bread\n[2] Ingredients".
    static func numbered(_ window: String) -> String {
        window.components(separatedBy: "\n").enumerated()
            .map { "[\($0.offset + 1)] \($0.element)" }
            .joined(separator: "\n")
    }

    /// The lines `pick` names in `window`, in page order, for `PageRecipeCheck` (which still keeps
    /// only one recipe card's). A run outside the window or backwards is no answer, never a guess;
    /// a line named as both an ingredient and a step is neither; a leading bullet or checkbox is
    /// dropped, and so is a line that is only an ingredients or steps heading ("Ingredients:").
    static func selection(_ window: String, _ pick: PagePick) -> PageSelection {
        let lines = window.components(separatedBy: "\n")
        func numbers(_ runs: [LineRun]) -> [Int] {
            let valid = runs.filter { 1 <= $0.first && $0.first <= $0.last && $0.last <= lines.count }
            return Set(valid.flatMap { Array($0.first...$0.last) }).sorted()
        }
        let ingredients = numbers(pick.ingredients)
        let steps = numbers(pick.steps)
        let both = Set(ingredients).intersection(steps)
        func text(_ numbers: [Int]) -> [String] {
            numbers.filter { !both.contains($0) }
                .map { trimmed(lines[$0 - 1]) }
                .filter { !$0.isEmpty && !RecipeTextWindow.isBareHeading($0) }
        }
        return PageSelection(
            name: pick.name, ingredients: text(ingredients), steps: text(steps),
            yield: pick.yield, prepTime: pick.prepTime, cookTime: pick.cookTime, totalTime: pick.totalTime
        )
    }

    /// Kotlin's `trimStart { it.isWhitespace() || it in BULLETS }.trimEnd()`.
    private static func trimmed(_ line: String) -> String {
        let units = Array(line.utf16)
        var start = 0
        while start < units.count, PageRecipeCheck.isWhitespace(units[start]) || bullets.contains(units[start]) { start += 1 }
        var end = units.count
        while end > start, PageRecipeCheck.isWhitespace(units[end - 1]) { end -= 1 }
        return String(decoding: units[start..<end], as: UTF16.self)
    }
}
