import Foundation

/// A recipe as printed (#318): the card the print dialog or a cookbook PDF draws, as an ordered
/// list of `Block`s. Pure, like `RecipeShareText`, and built from the same inputs: the lines
/// already rendered for the screen (scaled, converted), so nothing here scales, converts or
/// re-parses. Ticked lines print plain. The screen only measures and draws the blocks; where the
/// pages break is `paginate`, and a cookbook's page numbers are `startPages`, so both apps break
/// a recipe at the same block, or the same line of one taller than a page. A line-for-line port
/// of Android's `RecipePrint`.
enum RecipePrint {

    /// One thing on the page, top to bottom.
    enum Block: Equatable {
        /// The recipe's photo, when it is cached on the phone; the screen loads the URL.
        case photo(String)
        case title(String)
        /// The site it came from ("smittenkitchen.com"), quietly under the title.
        case credit(String)
        /// The serves line, then the times line (`RecipeShareText.metaLines`).
        case meta(String)
        case section(String)
        /// An ingredient group heading (`IngredientHeading`): no bullet, never scaled.
        case groupHeading(String)
        case ingredient(String)
        case step(Int, String)
        case note(String)
    }

    /// The section headings, from the screen's strings; `share` gives the serves and times words.
    struct Labels {
        var share: RecipeShareText.Labels
        var ingredients: String
        var instructions: String
        var notes: String

        static let english = Labels(share: .english, ingredients: "Ingredients", instructions: "Instructions", notes: "Notes")
    }

    /// `recipe`'s card. `ingredients` and `instructions` are the lines as shown; `note` is the
    /// cook's own (printed on a single card; a cookbook leaves it out, as Send as file does).
    /// The photo is placed when the recipe has one; the screen draws it only if it is cached.
    static func blocks(
        recipe: Recipe,
        servings: ServingsScale?,
        ingredients: [String],
        instructions: [String],
        sourceDomain: String?,
        note: String?,
        labels: Labels = .english
    ) -> [Block] {
        var blocks: [Block] = []
        if let image = recipe.image, !image.kTrimmed.isEmpty { blocks.append(.photo(image)) }
        blocks.append(.title(recipe.name))
        if let sourceDomain, !sourceDomain.kTrimmed.isEmpty { blocks.append(.credit(sourceDomain)) }
        blocks += RecipeShareText.metaLines(recipe, servings, labels.share).map { .meta($0) }
        let lines = ingredients.filter { !$0.kTrimmed.isEmpty }
        if !lines.isEmpty {
            blocks.append(.section(labels.ingredients))
            blocks += lines.map { IngredientHeading.isHeading($0) ? .groupHeading($0.kTrimmed) : .ingredient($0) }
        }
        let steps = instructions.filter { !$0.kTrimmed.isEmpty }
        if !steps.isEmpty {
            blocks.append(.section(labels.instructions))
            blocks += steps.enumerated().map { .step($0.offset + 1, $0.element) }
        }
        if let note = note?.kTrimmed, !note.isEmpty {
            blocks.append(.section(labels.notes))
            blocks.append(.note(note))
        }
        return blocks
    }

    /// Whether `block` must not end a page: the title and what sits under it, and headings, stay
    /// with the first line after them.
    static func keepsWithNext(_ block: Block) -> Bool {
        switch block {
        case .photo, .title, .credit, .meta, .section, .groupHeading: return true
        case .ingredient, .step, .note: return false
        }
    }

    /// A block measured for paper: the `space` above it (dropped at the top of a page) and the
    /// heights of its `lines`, top to bottom (a photo is one line); `keep` is `keepsWithNext`.
    struct Measured: Equatable {
        var space: Double
        var lines: [Double]
        var keep: Bool
        var height: Double { space + lines.reduce(0, +) }
    }

    /// Lines `first` until `end` of block `block` (both indexes), on `page` (from 0).
    struct Piece: Equatable {
        var block: Int
        var page: Int
        var first: Int
        var end: Int
    }

    /// Where `blocks` go on pages `pageHeight` tall, in order: each block as one `Piece`, or, a
    /// block taller than a page, as one piece per page it runs over, split between lines, so
    /// nothing is drawn off a page. A block that doesn't fit starts a new page, and so does a run
    /// of kept blocks with the line after it, unless that run is taller than a page by itself;
    /// a block taller than a page needs only its first line to start on a page.
    static func paginate(_ blocks: [Measured], pageHeight: Double) -> [Piece] {
        var pieces: [Piece] = []
        var page = 0
        var used = 0.0
        func start(_ i: Int) -> Double {
            let block = blocks[i]
            return block.height <= pageHeight ? block.height : block.space + (block.lines.first ?? 0)
        }
        for i in blocks.indices {
            var run = start(i)
            var j = i
            while j < blocks.count - 1 && blocks[j].keep {
                j += 1
                run += start(j)
            }
            let needed = run <= pageHeight ? run : start(i)
            if used > 0 && used + needed > pageHeight {
                page += 1
                used = 0
            }
            let block = blocks[i]
            if block.height <= pageHeight {
                pieces.append(Piece(block: i, page: page, first: 0, end: block.lines.count))
                used += block.height
                continue
            }
            var first = 0
            for (l, line) in block.lines.enumerated() {
                let height = line + (l == 0 ? block.space : 0)
                if used > 0 && used + height > pageHeight {
                    if l > first { pieces.append(Piece(block: i, page: page, first: first, end: l)) }
                    page += 1
                    used = 0
                    first = l
                }
                used += height
            }
            pieces.append(Piece(block: i, page: page, first: first, end: block.lines.count))
        }
        return pieces
    }

    /// A cookbook's page numbers (from 1): the title page is 1, then `contentsPages` pages of
    /// contents, then each recipe from a new page, taking `pageCounts` pages each in order.
    /// Returns the first page of each recipe, for the contents.
    static func startPages(_ pageCounts: [Int], contentsPages: Int) -> [Int] {
        var next = 1 + contentsPages + 1
        return pageCounts.map { count in
            defer { next += max(count, 1) }
            return next
        }
    }
}
