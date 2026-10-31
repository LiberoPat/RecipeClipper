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

    static let ink = UIColor(red: 0x1C / 255, green: 0x19 / 255, blue: 0x17 / 255, alpha: 1)
    static let muted = UIColor(red: 0x6B / 255, green: 0x62 / 255, blue: 0x59 / 255, alpha: 1)
    static let paprika = UIColor(red: 0xBF / 255, green: 0x4A / 255, blue: 0x2B / 255, alpha: 1)

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

/// One block measured for the page: `space` above it (dropped at the top of a page), its own
/// `height`, and how to draw it with its top at a given y.
struct LaidBlock {
    let space: CGFloat
    let height: CGFloat
    let keep: Bool
    let draw: (CGFloat) -> Void
}

private let drawing: NSStringDrawingOptions = [.usesLineFragmentOrigin, .usesFontLeading]

extension PrintStyle {
    /// `text` wrapped to `width` at `x`.
    func textBlock(_ text: NSAttributedString, space: CGFloat, keep: Bool, x: CGFloat? = nil, width: CGFloat? = nil,
                   marker: NSAttributedString? = nil) -> LaidBlock {
        let width = width ?? contentWidth
        let x = x ?? margin
        let height = ceil(text.boundingRect(with: CGSize(width: width, height: .greatestFiniteMagnitude), options: drawing, context: nil).height)
        return LaidBlock(space: space, height: height, keep: keep) { top in
            marker?.draw(at: CGPoint(x: margin, y: top))
            text.draw(with: CGRect(x: x, y: top, width: width, height: height), options: drawing, context: nil)
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
        return LaidBlock(space: 0, height: height + 14, keep: true) { top in
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
