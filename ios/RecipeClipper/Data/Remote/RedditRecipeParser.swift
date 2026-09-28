import Foundation

/// A Reddit post's `.json` listing to a recipe. Ported from Android's `RedditRecipeParser`.
/// The response is a two-element array, the post (a `t3`) then its comment tree (`t1`s with
/// their `replies`). In order:
///  1. **The post body**, if `selftext` splits cleanly (`RecipeTextSplitter`).
///  2. **The comments**: the poster's own (`is_submitter`), then the rest by score; the first
///     that splits (`RedditCommentScorer`). AutoModerator's comments are skipped, not the
///     replies under them.
///  3. **Nothing found**: `.noTranscription`, with the post's title and photo. A legitimate
///     outcome, not a failure: never guess a recipe from prose.
/// The name is the post title, the photo the post's image; a crosspost with no body or photo
/// of its own borrows the original's, and `read` names the original for the source, whose
/// comments may hold the recipe. Anything that isn't a post listing is `.noRecipeFound`.
///
/// Pure: JSON text in, a `ParseResult` out.
enum RedditRecipeParser {

    /// How deep the comment walk goes. Reddit itself stops nesting long before this.
    private static let maxDepth = 50

    /// What `read` made of a listing: the result and, for a crosspost, the id of the post it
    /// shares (`crosspost_parent` "t3_abc123" is "abc123").
    struct Reading {
        let result: ParseResult
        var crosspostOf: String?
    }

    static func parse(_ json: String, sourceUrl: String) -> ParseResult { read(json, sourceUrl: sourceUrl).result }

    static func read(_ json: String, sourceUrl: String) -> Reading {
        let noRecipe = Reading(result: .error(.noRecipeFound))
        // The JSON-LD parser's reader: it refuses absurd nesting before JSONSerialization runs.
        guard let listings = JsonLdRecipeParser.parseJson(json) as? [Any],
              let first = listings.first as? [String: Any],
              let child = ((first["data"] as? [String: Any])?["children"] as? [Any])?.first as? [String: Any],
              str(child, "kind") == "t3",
              let post = child["data"] as? [String: Any]
        else { return noRecipe }

        let title = JsonLdRecipeParser.stripHtml(str(post, "title"))
        if title.kIsBlank { return noRecipe }
        let original = (post["crosspost_parent_list"] as? [Any])?.first as? [String: Any]
        let image = imageOf(post) ?? original.flatMap(imageOf)
        let parent = str(post, "crosspost_parent")
        let parentId = parent.hasPrefix("t3_") ? String(parent.dropFirst(3)) : parent
        let crosspostOf = parentId.isEmpty ? nil : parentId

        var body = bodyOf(post)
        if body.kIsBlank { body = original.map(bodyOf) ?? "" }
        let commentListing = listings.count > 1 ? listings[1] as? [String: Any] : nil
        guard let split = RecipeTextSplitter.split(body)
                ?? RedditCommentScorer.pick(comments(commentListing)).flatMap(RecipeTextSplitter.split)
        else {
            let own = imagesOf(post)
            let images = own.isEmpty ? (original.map(imagesOf) ?? []) : own
            return Reading(
                result: .error(.noTranscription(title: title, imageUrl: image, imageUrls: images)),
                crosspostOf: crosspostOf
            )
        }

        return Reading(result: .success(Recipe(
            name: title,
            image: image,
            ingredients: split.ingredients,
            instructions: split.instructions,
            prepTime: split.prepTime,
            cookTime: split.cookTime,
            totalTime: split.totalTime,
            yield: split.yield,
            sourceUrl: sourceUrl,
            sourceType: .reddit,
            // Reddit declares no language: the words decide, else English (#14's rule).
            language: LanguageWords.resolve(declared: nil, page: nil) {
                LanguageWords.detectionText(name: title, ingredients: split.ingredients)
            }
        )))
    }

    /// A string field, or "" when it's absent, null or not a string.
    private static func str(_ json: [String: Any], _ key: String) -> String {
        json[key] as? String ?? ""
    }

    private static func bodyOf(_ post: [String: Any]) -> String {
        let text = str(post, "selftext")
        let trimmed = text.kTrimmed
        return trimmed == "[removed]" || trimmed == "[deleted]" ? "" : text
    }

    /// Every comment in the tree but AutoModerator's, depth first, in the order Reddit sent them.
    static func comments(_ listing: [String: Any]?) -> [RedditComment] {
        var out: [RedditComment] = []
        func walk(_ node: [String: Any]?, _ depth: Int) {
            guard let node, depth <= maxDepth,
                  let children = (node["data"] as? [String: Any])?["children"] as? [Any] else { return }
            for case let child as [String: Any] in children {
                guard str(child, "kind") == "t1", let data = child["data"] as? [String: Any] else { continue }
                let body = str(data, "body")
                if !body.kIsBlank && str(data, "author") != "AutoModerator" {
                    out.append(RedditComment(body: body, bySubmitter: data["is_submitter"] as? Bool ?? false))
                }
                walk(data["replies"] as? [String: Any], depth + 1) // "" when there are none
            }
        }
        walk(listing, 0)
        return out
    }

    private static let imageExtension = JRegex(#"\.(?:jpe?g|png|gif|webp)$"#, ignoreCase: true)

    /// The post's photo: Reddit's preview of it, else a gallery's first picture, else a link
    /// straight to an image. `&amp;` in these URLs is undone, and nothing else: it's a URL.
    static func imageOf(_ post: [String: Any]) -> String? {
        if let images = (post["preview"] as? [String: Any])?["images"] as? [Any],
           let source = (images.first as? [String: Any])?["source"] as? [String: Any] {
            let url = str(source, "url")
            if url.hasPrefix("http") { return unescape(url) }
        }

        if let items = (post["gallery_data"] as? [String: Any])?["items"] as? [Any],
           let firstId = (items.first as? [String: Any]).map({ str($0, "media_id") }), !firstId.isEmpty,
           let s = ((post["media_metadata"] as? [String: Any])?[firstId] as? [String: Any])?["s"] as? [String: Any] {
            let u = str(s, "u")
            let candidate = u.isEmpty ? str(s, "gif") : u
            if candidate.hasPrefix("http") { return unescape(candidate) }
        }

        let overridden = str(post, "url_overridden_by_dest")
        let link = overridden.isEmpty ? str(post, "url") : overridden
        let path = URLComponents(string: link)?.path ?? ""
        return link.hasPrefix("http") && imageExtension.containsMatch(in: path) ? unescape(link) : nil
    }

    /// Every picture of the post, in order, for reading its text (#198): each of a gallery's
    /// (`gallery_data` gives the order, `media_metadata` the full-size address), else the one
    /// `imageOf` finds.
    static func imagesOf(_ post: [String: Any]) -> [String] {
        if let items = (post["gallery_data"] as? [String: Any])?["items"] as? [Any],
           let metadata = post["media_metadata"] as? [String: Any] {
            let gallery: [String] = items.compactMap { item in
                let id = (item as? [String: Any]).map { str($0, "media_id") } ?? ""
                guard let s = (metadata[id] as? [String: Any])?["s"] as? [String: Any] else { return nil }
                let u = str(s, "u")
                let candidate = u.isEmpty ? str(s, "gif") : u
                return candidate.hasPrefix("http") ? unescape(candidate) : nil
            }
            if !gallery.isEmpty { return gallery }
        }
        return [imageOf(post)].compactMap { $0 }
    }

    private static func unescape(_ url: String) -> String {
        url.replacingOccurrences(of: "&amp;", with: "&")
    }
}
