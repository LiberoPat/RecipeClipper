import SwiftUI
import UIKit

/// Loads recipe photos through a disk-backed URLCache, so a photo seen once still shows
/// offline (Android's Coil does this by default). AsyncImage can't: it uses the shared session
/// with its small default cache and honours each server's cache headers, so most photos were
/// gone by the next launch.
final class ImageLoader {
    static let memoryCapacity = 50 * 1024 * 1024
    static let diskCapacity = 200 * 1024 * 1024

    /// The app's loader. Replaced by `configure()` at app start with one on the disk cache;
    /// until then (or if that never runs) it falls back to the shared cache.
    private(set) static var shared = ImageLoader(cache: .shared)

    /// Called once from RecipeClipperApp.init. A cache of its own, not `URLCache.shared`, so
    /// recipe pages fetched by BlogRecipeSource are never kept or served from it.
    static func configure() {
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("RecipeImages", isDirectory: true)
        let cache = URLCache(memoryCapacity: memoryCapacity, diskCapacity: diskCapacity, directory: directory)
        shared = ImageLoader(cache: cache)
    }

    let cache: URLCache
    private let session: URLSession

    /// `protocolClasses` is for tests: a stub URLProtocol stands in for the network.
    init(cache: URLCache, protocolClasses: [AnyClass]? = nil) {
        self.cache = cache
        let config = URLSessionConfiguration.default
        config.urlCache = cache
        config.requestCachePolicy = .returnCacheDataElseLoad
        if let protocolClasses { config.protocolClasses = protocolClasses }
        session = URLSession(configuration: config)
    }

    /// The image's bytes: from the cache whenever it holds them, however old (a recipe photo
    /// doesn't change), else from the network. A fetched response is stored explicitly rather
    /// than left to URLSession, which skips responses marked no-store or without validators,
    /// and those are exactly the ones that would then be missing offline.
    func data(for url: URL) async throws -> Data {
        let request = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad, timeoutInterval: 30)
        if let cached = cache.cachedResponse(for: request) { return cached.data }
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        cache.storeCachedResponse(CachedURLResponse(response: response, data: data), for: request)
        return data
    }
}

/// AsyncImage's `content`/`placeholder` shape, loading through `ImageLoader.shared`. The
/// placeholder shows while loading and if the load fails; callers keep their own framing and
/// clipping around it, as they did with AsyncImage.
struct CachedAsyncImage<Content: View, Placeholder: View>: View {
    private let url: URL?
    private let content: (Image) -> Content
    private let placeholder: () -> Placeholder

    @State private var image: UIImage?

    init(
        url: URL?,
        @ViewBuilder content: @escaping (Image) -> Content,
        @ViewBuilder placeholder: @escaping () -> Placeholder
    ) {
        self.url = url
        self.content = content
        self.placeholder = placeholder
    }

    var body: some View {
        Group {
            if let image {
                content(Image(uiImage: image))
            } else {
                placeholder()
            }
        }
        .task(id: url) {
            image = nil
            guard let url, let data = try? await ImageLoader.shared.data(for: url) else { return }
            guard !Task.isCancelled else { return }
            image = UIImage(data: data)
        }
    }
}
