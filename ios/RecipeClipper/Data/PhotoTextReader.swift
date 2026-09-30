import Foundation

/// A Reddit post with no recipe as text whose photos can be read (#198): its link (the shared
/// one), title and every picture, in order. Android's `PhotoPost`.
struct PhotoPost: Hashable, Sendable {
    let url: String
    let title: String
    let imageUrls: [String]
}

/// What reading a post's photos came to.
enum PhotoTextResult: Equatable, Sendable {
    /// Every line read, picture by picture in order; empty when the pictures hold no text.
    case read([PhotoLine])
    /// No picture could be fetched or read (offline, a refused or broken image).
    case failed
}

/// Reads the text in pictures on the device: a post's photos (#198), each fetched at full size
/// only now, or the cook's own pages (#226: a photo taken or picked, or an image shared in, as a
/// local file URL, never cached), then recognised (Vision on iOS, ML Kit's Latin model from Play
/// services on Android). A seam, so the ViewModel is tested with a fake and never sees Vision or
/// UIKit: pictures travel as URL strings.
protocol PhotoTextReader: Sendable {
    /// The lines of `imageUrls` (web links or file URLs), in order. A picture that fails is skipped; `.failed` only when
    /// every one did. Cancellation ends the read.
    func read(_ imageUrls: [String]) async -> PhotoTextResult
}

/// No reader (a test that never reads a photo): every read fails.
struct UnavailablePhotoTextReader: PhotoTextReader {
    func read(_ imageUrls: [String]) async -> PhotoTextResult { .failed }
}
