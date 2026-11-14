import UIKit

/// The printed card's look (#318): the theme's type, Fraunces over Karla, in ink, muted and
/// paprika on white paper, in points. Light whatever the phone's theme: it is for paper. Android's
/// `PrintStyle`.
struct PrintStyle {
    /// US Letter in the US and Canada, A4 elsewhere; the print dialog fits it to the paper.
    let page: CGRect
    let margin: CGFloat = 54
    var contentWidth: CGFloat { page.width - 2 * margin }
    var contentHeight: CGFloat { page.height - 2 * margin }

    init(region: String? = Locale.current.region?.identifier) {
        let letter = ["US", "CA", "MX", "PH"].contains(region ?? "")
        page = letter ? CGRect(x: 0, y: 0, width: 612, height: 792) : CGRect(x: 0, y: 0, width: 595, height: 842)
    }

    // The theme's light tokens: paper is always light.
    static let ink = Tokens.ink
    static let muted = Tokens.muted
    static let paprika = Tokens.paprika

    let title = AppFont.fraunces(24, weight: 500)
    let bookTitle = AppFont.fraunces(34, weight: 500)
    let section = AppFont.fraunces(15, weight: 500)
    let group = AppFont.fraunces(12, weight: 500)
    let stepNumber = AppFont.fraunces(11.5, weight: 600)
    let text = AppFont.karla(11, weight: 400)
    let small = AppFont.karla(10, weight: 400)

    func string(_ s: String, _ font: UIFont, _ color: UIColor, centre: Bool = false) -> NSAttributedString {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineHeightMultiple = 1.15
        paragraph.alignment = centre ? .center : .natural
        return NSAttributedString(string: s, attributes: [.font: font, .foregroundColor: color, .paragraphStyle: paragraph])
    }
}

/// One block measured for the page: `space` above it (dropped at the top of a page), the heights
/// of its `lines` (a photo is one), and how to draw lines `first` until `end` of it with the
/// first's top at a given y: a block taller than a page runs over several (`RecipePrint.paginate`).
struct LaidBlock {
    let space: CGFloat
    let lines: [CGFloat]
    let keep: Bool
    let draw: (_ top: CGFloat, _ first: Int, _ end: Int) -> Void

    /// The height of lines `first` until `end`.
    func height(_ first: Int, _ end: Int) -> CGFloat { lines[first..<end].reduce(0, +) }

    var measured: RecipePrint.Measured { .init(space: Double(space), lines: lines.map(Double.init), keep: keep) }
}

/// `text` wrapped to `width` (TextKit, so each line is known): its lines' heights, and drawing
/// of a run of them, the rest clipped away. Made and used on one thread.
final class WrappedText {
    private let storage: NSTextStorage
    private let manager = NSLayoutManager()
    private let container: NSTextContainer
    private let tops: [CGFloat]
    let lines: [CGFloat]

    init(_ text: NSAttributedString, width: CGFloat) {
        storage = NSTextStorage(attributedString: text)
        container = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        manager.addTextContainer(container)
        storage.addLayoutManager(manager)
        var tops: [CGFloat] = []
        var heights: [CGFloat] = []
        manager.enumerateLineFragments(forGlyphRange: manager.glyphRange(for: container)) { rect, _, _, _, _ in
            tops.append(rect.minY)
            heights.append(rect.height)
        }
        self.tops = tops
        self.lines = heights
    }

    /// Lines `first` until `end`, the first's top at `origin`.
    func draw(from first: Int, to end: Int, at origin: CGPoint) {
        guard end > first, let context = UIGraphicsGetCurrentContext() else { return }
        let top = tops[first]
        let bottom = tops[end - 1] + lines[end - 1]
        context.saveGState()
        context.clip(to: CGRect(x: origin.x - 100, y: origin.y, width: container.size.width + 200, height: bottom - top))
        manager.drawGlyphs(forGlyphRange: manager.glyphRange(for: container), at: CGPoint(x: origin.x, y: origin.y - top))
        context.restoreGState()
    }
}

extension PrintStyle {
    /// `text` wrapped to `width` at `x`; `marker` ("•" or "3.") sits in the margin beside its first line.
    func textBlock(_ text: NSAttributedString, space: CGFloat, keep: Bool, x: CGFloat? = nil, width: CGFloat? = nil,
                   marker: NSAttributedString? = nil) -> LaidBlock {
        let width = width ?? contentWidth
        let x = x ?? margin
        let wrapped = WrappedText(text, width: width)
        return LaidBlock(space: space, lines: wrapped.lines, keep: keep) { top, first, end in
            if first == 0 { marker?.draw(at: CGPoint(x: margin, y: top)) }
            wrapped.draw(from: first, to: end, at: CGPoint(x: x, y: top))
        }
    }

    /// `blocks` measured; a photo only when `photos` has it (it was cached on the phone).
    func measure(_ blocks: [RecipePrint.Block], photos: [String: UIImage]) -> [LaidBlock] {
        blocks.compactMap { block in
            let keep = RecipePrint.keepsWithNext(block)
            switch block {
            case .photo(let url): return photos[url].map(photo)
            case .title(let s): return textBlock(string(s, title, Self.ink), space: 0, keep: keep)
            case .credit(let s): return textBlock(string(s, small, Self.muted), space: 4, keep: keep)
            case .meta(let s): return textBlock(string(s, small, Self.muted), space: 6, keep: keep)
            case .section(let s): return textBlock(string(s, section, Self.paprika), space: 18, keep: keep)
            case .groupHeading(let s): return textBlock(string(s, group, Self.muted), space: 8, keep: keep)
            case .ingredient(let s):
                return textBlock(string(s, text, Self.ink), space: 4, keep: false, x: margin + 14, width: contentWidth - 14,
                                 marker: string("•", small, Self.muted))
            case .step(let n, let s):
                return textBlock(string(s, text, Self.ink), space: 8, keep: false, x: margin + 22, width: contentWidth - 22,
                                 marker: string("\(n).", stepNumber, Self.paprika))
            case .note(let s): return textBlock(string(s, text, Self.ink), space: 4, keep: keep)
            }
        }
    }

    /// The photo across the page, cropped from its centre to a band at most 230 points tall.
    private func photo(_ image: UIImage) -> LaidBlock {
        let height = min(contentWidth * 9 / 16, 230)
        return LaidBlock(space: 0, lines: [height + 14], keep: true) { top, _, _ in
            let box = CGRect(x: margin, y: top, width: contentWidth, height: height)
            guard image.size.width > 0, image.size.height > 0, let context = UIGraphicsGetCurrentContext() else { return }
            let scale = max(box.width / image.size.width, box.height / image.size.height)
            let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
            context.saveGState()
            context.clip(to: box)
            image.draw(in: CGRect(x: box.midX - size.width / 2, y: box.midY - size.height / 2, width: size.width, height: size.height))
            context.restoreGState()
        }
    }
}
