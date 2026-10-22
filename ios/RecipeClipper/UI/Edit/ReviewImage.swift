import ImageIO
import SwiftUI
import UIKit

/// Pictures for the review (#286), decoded small and never whole: a page's thumbnail, or the strip
/// of a page a line was read from. Each is decoded the right way up (its EXIF orientation applied,
/// as Vision read it), so a line's box lands on the same place.
enum ReviewImages {
    /// A page is decoded at most this many pixels a side before a strip is cut from it.
    static let stripPageSide = 1600

    /// `data`'s picture the right way up, at most `maxSide` pixels a side.
    static func downsampled(_ data: Data, maxSide: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// The strip of `data`'s picture at `box` with a little room around it (`PhotoBox.padded`),
    /// drawn into an image of its own, so the page it was cut from isn't kept with it.
    static func strip(_ data: Data, box: PhotoBox) -> UIImage? {
        guard let page = downsampled(data, maxSide: stripPageSide) else { return nil }
        let rect = pixels(box.padded(width: page.width, height: page.height), width: page.width, height: page.height)
        guard let cut = page.cropping(to: rect) else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: rect.size, format: format).image { _ in
            UIImage(cgImage: cut).draw(in: CGRect(origin: .zero, size: rect.size))
        }
    }

    /// `strip` in whole pixels of a `width` by `height` picture, at least one pixel each way and
    /// inside the picture. Android's `stripPixels`.
    static func pixels(_ strip: PhotoBox, width: Int, height: Int) -> CGRect {
        let left = min(max(Int(strip.left * Float(width)), 0), width - 1)
        let top = min(max(Int(strip.top * Float(height)), 0), height - 1)
        let right = min(max(Int((strip.right * Float(width)).rounded(.up)), left + 1), width)
        let bottom = min(max(Int((strip.bottom * Float(height)).rounded(.up)), top + 1), height)
        return CGRect(x: left, y: top, width: right - left, height: bottom - top)
    }

    /// A strip's height on a card `width` wide, for a strip `aspect` times as wide as it is tall:
    /// as tall as the width allows, between 28 and 56 points. Android's `stripHeight`.
    static func stripHeight(width: CGFloat, aspect: CGFloat) -> CGFloat {
        guard aspect > 0 else { return 28 }
        return min(max(width / aspect, 28), 56)
    }
}

/// A picture read, decoded small off the main thread (#286): through `ImageLoader`, so a post's
/// picture comes from the cache the read filled (and is fetched again only if the cache has let it
/// go), and a scan's page from its file, never cached. `box` cuts a line's strip from it; without
/// one it is the page, at most `maxSide` pixels a side. Nil until it loads, or if it can't.
struct ReviewImage<Content: View>: View {
    let url: String
    let box: PhotoBox?
    let maxSide: Int
    let content: (UIImage?) -> Content

    init(url: String, box: PhotoBox? = nil, maxSide: Int = 240, @ViewBuilder content: @escaping (UIImage?) -> Content) {
        self.url = url
        self.box = box
        self.maxSide = maxSide
        self.content = content
    }

    @State private var image: UIImage?

    var body: some View {
        content(image)
            .task(id: "\(url) \(String(describing: box)) \(maxSide)") {
                image = nil
                guard let link = URL(string: url), let data = try? await ImageLoader.shared.data(for: link) else { return }
                let box = box, maxSide = maxSide
                let made = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                    if let box { return ReviewImages.strip(data, box: box) }
                    return ReviewImages.downsampled(data, maxSide: maxSide).map { UIImage(cgImage: $0) }
                }.value
                guard !Task.isCancelled else { return }
                image = made
            }
    }
}

/// The strip of the photo a line was read from (#286): as tall as the card's width allows,
/// between 28 and 56 points; past that a long line's end is cut, never its start, where the
/// amount is. A hairline band holds its place until it loads. Android's `Strip`.
struct StripView: View {
    let strip: PhotoStrip
    @State private var width: CGFloat = 0

    var body: some View {
        ReviewImage(url: strip.image, box: strip.box) { image in
            if let image {
                let aspect = image.size.width / max(image.size.height, 1)
                let height = ReviewImages.stripHeight(width: width, aspect: aspect)
                Image(uiImage: image)
                    .resizable()
                    .frame(width: height * aspect, height: height)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(height: height)
                    .clipped()
            } else {
                Palette.hairline.frame(height: 28)
            }
        }
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width = $0 }
        .accessibilityElement()
        .accessibilityLabel(Strings.photoStripDescription)
        .accessibilityAddTraits(.isImage)
    }
}
