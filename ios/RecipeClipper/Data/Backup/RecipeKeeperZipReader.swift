import Foundation

/// Reads a Recipe Keeper export (#390; Android's `RecipeKeeperArchive`): a zip with `recipes.html`
/// (at the root, or inside one wrapper folder) and `images/<recipeId>_<N>.jpg`. The zip structure,
/// DEFLATE and the caps are `PaprikaZipReader`'s (Apple's Compression framework, no third-party
/// dependency): an entry's declared size is a claim, so every unpacking counts what it really
/// produces and stops at the cap. Entries are unpacked in memory one at a time, an entry's name is
/// only ever compared (never used as a path), and nothing is written to disk. The caps are
/// `PaprikaLimits`, the same as the Paprika file's: a page over `maxEntryBytes`, more entries than
/// `maxEntries`, or more unpacked than `maxTotalBytes` is refused for the whole file.
///
/// The recipes come back as `PaprikaRecipe`s, the shape both imports share, with `uid` the recipe's
/// `recipeId` and its first readable picture stored through `storePhoto` as the file is read (#386;
/// the next one is tried if the first is missing from the zip, too big or not an image). A recipe
/// with pictures none of which could be stored keeps the recipe and counts one picture left out. A
/// page with no `div.recipe-details` at all is not Recipe Keeper's.
///
/// One difference from Android: a page that lies about its size (its headers say small, it unpacks
/// past the cap) reads as `.damaged` here and `.tooLarge` there, since the inflater only says it
/// failed. Both import nothing.
enum RecipeKeeperZipReader {
    private static let page = "recipes.html"
    private static let maxPhotosTried = 10

    /// True for an entry that may be the page: a file called `recipes.html`, not hidden or a macOS copy.
    static func isPageEntry(_ name: String) -> Bool {
        if name.hasSuffix("/") || name.hasPrefix("__MACOSX/") || name.contains("/__MACOSX/") { return false }
        let file = name.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? name
        return !file.hasPrefix(".") && file.lowercased() == page
    }

    /// `storePhoto` answers the stored file's name, or nil for bytes that aren't a picture.
    static func read(
        _ zip: Data, limits: PaprikaLimits = PaprikaLimits(), storePhoto: ((Data) -> String?)? = nil
    ) -> PaprikaRead {
        guard zip.count >= 4, PaprikaZipReader.u32(zip, 0) == PaprikaZipReader.localSignature else {
            return .failure(.notARecipeKeeperFile)
        }
        guard zip.count <= limits.maxFileBytes else { return .failure(.tooLarge) }
        guard let (entries, declared) = PaprikaZipReader.centralDirectory(zip) else { return .failure(.damaged) }
        if declared > limits.maxEntries { return .failure(.tooLarge) }

        var byName: [String: PaprikaZipReader.Entry] = [:]
        var pageEntry: PaprikaZipReader.Entry?
        for entry in entries where !entry.name.hasSuffix("/") {
            let key = entry.name.lowercased()
            if byName[key] == nil { byName[key] = entry }
            // The shallowest page wins: the root's, else the wrapper folder's.
            if isPageEntry(entry.name), pageEntry.map({ depth(entry.name) < depth($0.name) }) ?? true { pageEntry = entry }
        }
        guard let pageEntry else { return .failure(.notARecipeKeeperFile) }
        if Int(pageEntry.size) > limits.maxEntryBytes || Int(pageEntry.compressedSize) > limits.maxEntryBytes {
            return .failure(.tooLarge)
        }
        guard let bytes = PaprikaZipReader.contents(of: pageEntry, in: zip, max: limits.maxEntryBytes) else {
            return .failure(.damaged)
        }
        var total = bytes.count
        if total > limits.maxTotalBytes { return .failure(.tooLarge) }

        // Lenient on purpose: one stray byte shouldn't cost a whole library. The page is UTF-8.
        let parsed = RecipeKeeperParser.parse(String(decoding: bytes, as: UTF8.self))
        if parsed.blocks == 0 { return .failure(.notARecipeKeeperFile) }

        let folder = pageEntry.name.split(separator: "/", omittingEmptySubsequences: false).dropLast().joined(separator: "/")
        var recipes: [PaprikaRecipe] = []
        recipes.reserveCapacity(parsed.recipes.count)
        var photosLeftOut = 0
        for recipe in parsed.recipes {
            var stored: String?
            for src in recipe.photos.prefix(maxPhotosTried) {
                guard let name = resolve(folder: folder, src: src), let entry = byName[name.lowercased()] else { continue }
                if Int(entry.size) > limits.maxPhotoBytes || Int(entry.compressedSize) > limits.maxPhotoBytes { continue }
                guard let picture = PaprikaZipReader.contents(of: entry, in: zip, max: limits.maxPhotoBytes) else { continue }
                total += picture.count
                if total > limits.maxTotalBytes { return .failure(.tooLarge) }
                stored = storePhoto?(picture)
                if stored != nil { break }
            }
            if stored == nil && !recipe.photos.isEmpty { photosLeftOut += 1 }
            recipes.append(PaprikaRecipe(
                uid: recipe.id,
                name: recipe.name,
                ingredients: recipe.ingredients,
                directions: recipe.directions,
                notes: recipe.notes,
                servings: recipe.servings,
                prepTime: recipe.prepTime,
                cookTime: recipe.cookTime,
                totalTime: nil,
                sourceUrl: recipe.sourceUrl,
                imageUrl: nil,
                categories: recipe.categories,
                hasPhoto: !recipe.photos.isEmpty,
                photoFile: stored,
                favorite: recipe.favorite
            ))
        }
        return .success(PaprikaContents(recipes: recipes, notRecipes: parsed.notRecipes, unreadable: 0, photosLeftOut: photosLeftOut))
    }

    private static func depth(_ name: String) -> Int { name.reduce(0) { $1 == "/" ? $0 + 1 : $0 } }

    /// The zip entry name a picture's `src` means, relative to the page's `folder`: `.` and `..` are
    /// folded away inside the zip's own namespace, so an address can't reach outside it; a link with a
    /// scheme, an absolute path or a backslash is none. A percent-encoded name is read decoded. Only
    /// ever compared with entry names, never opened as a path.
    static func resolve(folder: String, src: String) -> String? {
        let decoded = src.removingPercentEncoding ?? src
        if decoded.kIsBlank || decoded.contains(":") || decoded.contains("\\") || decoded.hasPrefix("/") { return nil }
        var parts: [Substring] = []
        for segment in "\(folder)/\(decoded)".split(separator: "/", omittingEmptySubsequences: false) {
            switch segment {
            case "", ".": continue
            case "..":
                if parts.isEmpty { return nil }
                parts.removeLast()
            default: parts.append(segment)
            }
        }
        return parts.isEmpty ? nil : parts.joined(separator: "/")
    }
}
