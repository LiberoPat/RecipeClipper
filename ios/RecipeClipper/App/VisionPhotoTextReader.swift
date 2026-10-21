import Foundation
import ImageIO
import Vision

/// Reads a post's photos (#198), or the cook's own pages (#226, file URLs), with Vision: `VNRecognizeTextRequest` at the accurate level,
/// with language correction, hinted with the phone's language and English, on the device. Each
/// picture is fetched at full size through the app's `ImageLoader` (so one already shown comes
/// from its cache; a local page is read from its file, never cached). A camera photo is read the
/// way up its EXIF orientation says. Lines come in Vision's order, each with its top candidate's confidence.
/// Android's twin is `MlKitPhotoTextReader`.
struct VisionPhotoTextReader: PhotoTextReader {
    func read(_ imageUrls: [String]) async -> PhotoTextResult {
        var anyRead = false
        var lines: [PhotoLine] = []
        for (page, string) in imageUrls.enumerated() {
            if Task.isCancelled { return .failed }
            guard let url = URL(string: string), let data = try? await ImageLoader.shared.data(for: url) else { continue }
            guard let read = await Self.recognise(data, page: page) else { continue } // this picture failed; the others may still read
            anyRead = true
            lines += read
        }
        return anyRead ? .read(lines) : .failed
    }

    /// The picture's lines, or nil when Vision couldn't read it at all, each with `page` (its
    /// index in the list) and where on it it was read (#286). Off the main thread: `perform`
    /// blocks until it is done.
    private static func recognise(_ data: Data, page: Int) async -> [PhotoLine]? {
        await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.recognitionLanguages = languages(supported: (try? request.supportedRecognitionLanguages()) ?? [])
            do {
                try VNImageRequestHandler(data: data, orientation: orientation(of: data), options: [:]).perform([request])
            } catch {
                return nil
            }
            return (request.results ?? []).compactMap { observation -> PhotoLine? in
                guard let best = observation.topCandidates(1).first else { return nil }
                return PhotoLine(text: best.string, confidence: best.confidence, page: page, box: box(observation.boundingBox))
            }
        }.value
    }

    /// Vision's `boundingBox` as the review's box (#286). Vision's is normalised with its origin
    /// at the bottom left, in the picture the way up its EXIF orientation says (the handler is
    /// given it); the box's origin is the top left, so y is flipped. Clamped to the picture; nil
    /// if nothing of it is on the picture.
    static func box(_ rect: CGRect) -> PhotoBox? {
        func clamp(_ value: CGFloat) -> Float { Float(min(max(value, 0), 1)) }
        let left = clamp(rect.minX), right = clamp(rect.maxX)
        let top = clamp(1 - rect.maxY), bottom = clamp(1 - rect.minY)
        guard right > left, bottom > top else { return nil }
        return PhotoBox(left: left, top: top, right: right, bottom: bottom)
    }

    /// The picture's EXIF orientation (a phone's camera photo is usually stored sideways), up
    /// when it says none.
    private static func orientation(of data: Data) -> CGImagePropertyOrientation {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let raw = (properties[kCGImagePropertyOrientation] as? NSNumber)?.uint32Value,
              let orientation = CGImagePropertyOrientation(rawValue: raw)
        else { return .up }
        return orientation
    }

    /// The phone's languages the recogniser knows, then English: the recipe's own language
    /// isn't known until it is read, and most Reddit recipes are English.
    static func languages(supported: [String]) -> [String] {
        var out: [String] = []
        for preferred in Locale.preferredLanguages + ["en-US"] {
            let code = preferred.split(separator: "-").first.map(String.init) ?? preferred
            if let match = supported.first(where: { $0 == preferred || $0.hasPrefix(code + "-") || $0 == code }),
               !out.contains(match) {
                out.append(match)
            }
        }
        return out
    }
}
