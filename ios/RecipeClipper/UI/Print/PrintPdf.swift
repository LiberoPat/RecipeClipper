import UIKit

/// Draws printed recipes (#318) into a PDF: one recipe for the print dialog, or a list as a
/// cookbook (a title page, contents, each recipe from a new page, numbered pages). Where pages
/// break is `RecipePrint.paginate`'s; this only measures and draws. A photo prints only if it is
/// already cached (never fetched for paper). Android's `PrintPdf`.
struct PrintPdf {
    var style = PrintStyle()

    /// The photos `blocks` place, from what is already on the phone.
    static func cachedPhotos(_ blocks: [RecipePrint.Block], loader: ImageLoader = .shared) -> [String: UIImage] {
        var photos: [String: UIImage] = [:]
        for case .photo(let url) in blocks {
            if let link = URL(string: url), let data = loader.cachedData(for: link), let image = UIImage(data: data) {
                photos[url] = image
            }
        }
        return photos
    }

    /// One recipe's `blocks`.
    func recipe(_ blocks: [RecipePrint.Block], photos: [String: UIImage]) -> Data {
        let pages = paged(style.measure(blocks, photos: photos))
        return renderer.pdfData { context in
            for page in pages { draw(page, in: context, footer: nil) }
        }
    }

    /// A cookbook named `title` of `recipes` (each name with its blocks), contents headed `contents`.
    func cookbook(title: String, contents: String, recipes: [(String, [RecipePrint.Block])], photos: [String: UIImage]) -> Data {
        let pagesOf = recipes.map { paged(style.measure($0.1, photos: photos)) }.map { $0.isEmpty ? [[]] : $0 }
        var starts = Array(repeating: 0, count: recipes.count)
        let heading = style.textBlock(style.string(contents, style.title, PrintStyle.ink), space: 0, keep: true)
        let rows = recipes.indices.map { i in contentsRow(recipes[i].0) { starts[i] } }
        let headed = LaidBlock(space: 0, lines: [heading.height(0, heading.lines.count) + 12], keep: true) { top, _, _ in
            heading.draw(top, 0, heading.lines.count)
        }
        let contentsPages = paged([headed] + rows)
        starts = RecipePrint.startPages(pagesOf.map(\.count), contentsPages: contentsPages.count)

        return renderer.pdfData { context in
            context.beginPage()
            let name = style.string(title, style.bookTitle, PrintStyle.ink, centre: true)
            let block = style.textBlock(name, space: 0, keep: false)
            block.draw((style.page.height - block.height(0, block.lines.count)) / 2, 0, block.lines.count)
            var number = 2
            for page in contentsPages + pagesOf.flatMap({ $0 }) {
                draw(page, in: context, footer: "\(number)")
                number += 1
            }
        }
    }

    private var renderer: UIGraphicsPDFRenderer { UIGraphicsPDFRenderer(bounds: style.page) }

    private func contentsRow(_ name: String, page: @escaping () -> Int) -> LaidBlock {
        let row = style.textBlock(style.string(name, style.text, PrintStyle.ink), space: 8, keep: false, width: style.contentWidth - 40)
        return LaidBlock(space: 8, lines: [row.height(0, row.lines.count)], keep: false) { top, _, _ in
            row.draw(top, 0, row.lines.count)
            let number = style.string("\(page())", style.small, PrintStyle.muted)
            number.draw(at: CGPoint(x: style.margin + style.contentWidth - number.size().width, y: top + 1))
        }
    }

    /// A block, or the lines of one, on a page.
    private struct Placed {
        let block: LaidBlock
        let piece: RecipePrint.Piece
    }

    private func paged(_ laid: [LaidBlock]) -> [[Placed]] {
        let pieces = RecipePrint.paginate(laid.map(\.measured), pageHeight: Double(style.contentHeight))
        var result: [[Placed]] = []
        for piece in pieces {
            if piece.page >= result.count { result.append([]) }
            result[result.count - 1].append(Placed(block: laid[piece.block], piece: piece))
        }
        return result
    }

    private func draw(_ placed: [Placed], in context: UIGraphicsPDFRendererContext, footer: String?) {
        context.beginPage()
        var y = style.margin
        // Nothing is drawn below the content area (#331); the footer sits in the margin.
        context.cgContext.saveGState()
        context.cgContext.clip(to: CGRect(x: 0, y: style.margin, width: style.page.width, height: style.contentHeight))
        for (i, item) in placed.enumerated() {
            if i > 0 && item.piece.first == 0 { y += item.block.space }
            item.block.draw(y, item.piece.first, item.piece.end)
            y += item.block.height(item.piece.first, item.piece.end)
        }
        context.cgContext.restoreGState()
        if let footer {
            let text = style.string(footer, style.small, PrintStyle.muted)
            let size = text.size()
            text.draw(at: CGPoint(x: (style.page.width - size.width) / 2, y: style.page.height - style.margin / 2 - size.height))
        }
    }
}
