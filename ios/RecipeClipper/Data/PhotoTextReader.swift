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

/// Reads the text in a post's photos on the device (#198): each picture is fetched at full size
/// only now, then recognised (Vision on iOS, ML Kit's bundled Latin model on Android). A seam, so
/// the ViewModel is tested with a fake and never sees Vision or UIKit.
protocol PhotoTextReader: Sendable {
    /// The lines of `imageUrls`, in order. A picture that fails is skipped; `.failed` only when
    /// every one did. Cancellation ends the read.
    func read(_ imageUrls: [String]) async -> PhotoTextResult
}

/// No reader (a test that never reads a photo): every read fails.
struct UnavailablePhotoTextReader: PhotoTextReader {
    func read(_ imageUrls: [String]) async -> PhotoTextResult { .failed }
}
