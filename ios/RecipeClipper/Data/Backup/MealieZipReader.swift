import Foundation

enum MealieRead: Equatable {
    case success(MealieContents)
    case failure(MealieError)
}

/// Why a Mealie file wasn't imported (#391; Android's `MealieError`): the cause, never the words (the
/// screen picks those).
enum MealieError: Equatable {
    /// Not a zip with a Mealie recipe in it, and not a Mealie recipe's JSON: a recipe page's schema.org
    /// data (read through the link), another app's file, or a file that isn't JSON.
    case notAMealieFile
    /// A zip that is cut short or broken in its structure. Nothing was imported.
    case damaged
    /// More entries, or more unpacked text, than any real export holds (`MealieLimits`).
    case tooLarge
    /// The file couldn't be opened.
    case readFailed
    /// The database refused the write; the import is one transaction, so nothing was written.
    case saveFailed
}

/// How much of a file is believed (#391; Android's `MealieLimits`), the same numbers as the Paprika
/// import's. A hostile file can name a million entries or unpack a few kilobytes to gigabytes, so
/// nothing is trusted: an entry's declared size is checked, and its real size is counted as it unpacks.
/// Tests pass small ones.
struct MealieLimits: Equatable {
    var maxEntries = 10_000
    /// One recipe's unpacked JSON.
    var maxEntryBytes = 32 * 1024 * 1024
    /// Every entry that is read, together (pictures included).
    var maxTotalBytes = 2 * 1024 * 1024 * 1024
    /// The file itself.
    var maxFileBytes = 2 * 1024 * 1024 * 1024
    /// One recipe's picture (#386): bigger is left out, the recipe kept.
    var maxPhotoBytes = 8 * 1024 * 1024
}

/// What a Mealie file held: its readable recipes, and how many entries weren't.
struct MealieContents: Equatable {
    var recipes: [MealieRecipe]
    /// JSON files that aren't recipes: shaped like one with no name or content, or not Mealie's at all.
    var notRecipes: Int
    /// JSON files that couldn't be unpacked or read.
    var unreadable: Int
    /// Pictures that came with a recipe but were left out: too big, not an image, or not stored (#386). The recipe stays.
    var photosLeftOut = 0
}

/// Reads a Mealie file (#391; Android's `MealieArchive`): the bulk export (a zip of
/// `recipes/<slug>/<slug>.json`, no pictures), the per-recipe download (a zip of one `.json` and one
/// `.webp`), or a bare `.json`. The zip structure and the inflating are `PaprikaZipReader`'s (a small zip
/// reader over Apple's Compression framework, no third-party dependency), with the limits a stranger's file
/// needs: an entry's declared size is a claim, so every unpacking counts what it really produces and stops
/// at the cap. Entries are unpacked in memory one at a time, an entry's name is only ever compared (never
/// used as a path), and nothing is written to disk. Entries that aren't JSON or a picture (folders,
/// macOS's `__MACOSX/._…` copies, a readme) are ignored and not counted. One broken entry is counted as
/// unreadable and the rest still import; a broken zip imports nothing.
///
/// **A picture belongs to the recipe whose JSON is in its folder** (the nearest one up the path:
/// `recipes/<slug>/images/original.webp` to `recipes/<slug>/<slug>.json`); a picture beside the only JSON
/// in the zip belongs to it wherever it sits. A picture that fits no recipe is ignored.
///
/// It lives outside the pure logic (`CoreImportsTests` allows Foundation only there) because of Compression;
/// the parsing it hands to is pure.
enum MealieZipReader {
    private static let pictureExtensions = [".webp", ".jpg", ".jpeg", ".png"]
    private static let localSignature: UInt32 = 0x0403_4B50

    private static func visible(_ name: String) -> Bool {
        if name.hasSuffix("/") || name.hasPrefix("__MACOSX/") || name.contains("/__MACOSX/") { return false }
        let file = name.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? name
        return !file.hasPrefix(".")
    }

    /// True for an entry that may be a recipe: a file named `*.json`, not a hidden or macOS copy.
    static func isRecipeEntry(_ name: String) -> Bool { visible(name) && name.lowercased().hasSuffix(".json") }

    /// True for an entry that may be a recipe's picture: a `.webp`, `.jpg`, `.jpeg` or `.png`, not hidden or a macOS copy.
    static func isPictureEntry(_ name: String) -> Bool {
        visible(name) && pictureExtensions.contains { name.lowercased().hasSuffix($0) }
    }

    /// `storePhoto` (#386) is handed each recipe's picture (within `MealieLimits.maxPhotoBytes`) as its
    /// recipe is read, so no picture waits in memory for the rest of the file; it answers the stored file's
    /// name, or nil for a picture that isn't one (left out and counted, the recipe stays). Called on this
    /// thread, once per recipe.
    static func read(
        _ file: Data, limits: MealieLimits = MealieLimits(), storePhoto: ((Data) -> String?)? = nil
    ) -> MealieRead {
        if file.count >= 4, PaprikaZipReader.u32(file, 0) == localSignature { return readZip(file, limits, storePhoto) }
        return readBare(file, limits)
    }

    /// A bare `.json`: one recipe, or no Mealie file at all.
    private static func readBare(_ file: Data, _ limits: MealieLimits) -> MealieRead {
        if file.count > limits.maxEntryBytes { return .failure(.tooLarge) }
        guard let text = String(data: file, encoding: .utf8) else { return .failure(.notAMealieFile) }
        switch MealieRecipeParser.parse(text) {
        case .recipe(let recipe): return .success(MealieContents(recipes: [recipe], notRecipes: 0, unreadable: 0))
        case .notARecipe: return .success(MealieContents(recipes: [], notRecipes: 1, unreadable: 0))
        case .notMealie, .unreadable: return .failure(.notAMealieFile)
        }
    }

    private static func readZip(_ zip: Data, _ limits: MealieLimits, _ storePhoto: ((Data) -> String?)?) -> MealieRead {
        guard zip.count <= limits.maxFileBytes else { return .failure(.tooLarge) }
        guard let (entries, declared) = PaprikaZipReader.centralDirectory(zip) else { return .failure(.damaged) }
        if declared > limits.maxEntries { return .failure(.tooLarge) }

        let jsons = entries.filter { isRecipeEntry($0.name) }
        let pictures = entries.filter { isPictureEntry($0.name) }
        if jsons.isEmpty { return .failure(.notAMealieFile) }
        let pictureOf = assign(jsons.map(\.name), pictures)

        var recipes: [MealieRecipe] = []
        var notRecipes = 0
        var unreadable = 0
        var shaped = 0
        var photosLeftOut = 0
        var total = 0
        for entry in jsons {
            // A declared size is a claim: over the cap it is refused without unpacking anything.
            if Int(entry.size) > limits.maxEntryBytes || Int(entry.compressedSize) > limits.maxEntryBytes {
                unreadable += 1
                continue
            }
            guard let bytes = PaprikaZipReader.contents(of: entry, in: zip, max: limits.maxEntryBytes) else {
                unreadable += 1
                continue
            }
            total += bytes.count
            if total > limits.maxTotalBytes { return .failure(.tooLarge) }
            guard let text = String(data: bytes, encoding: .utf8) else {
                unreadable += 1
                continue
            }
            switch MealieRecipeParser.parse(text) {
            case .recipe(var recipe):
                shaped += 1
                if let picture = pictureOf[entry.name] {
                    let data = readPicture(zip, picture, limits)
                    total += data?.count ?? 0
                    if total > limits.maxTotalBytes { return .failure(.tooLarge) }
                    let stored = data.flatMap { storePhoto?($0) }
                    if stored == nil { photosLeftOut += 1 }
                    recipe.photoFile = stored
                }
                recipes.append(recipe)
            case .notARecipe:
                shaped += 1
                notRecipes += 1
            case .notMealie: notRecipes += 1
            case .unreadable: unreadable += 1
            }
        }
        // JSON files and not one of them Mealie's (a schema.org file, another app's): not a Mealie export.
        if shaped == 0 && unreadable == 0 { return .failure(.notAMealieFile) }
        return .success(MealieContents(recipes: recipes, notRecipes: notRecipes, unreadable: unreadable, photosLeftOut: photosLeftOut))
    }

    /// Each recipe entry's picture, by name only: the nearest recipe JSON up the picture's path, and a
    /// folder-less zip's only recipe. Of several pictures for one recipe, one named "original" wins, else
    /// the first in the zip.
    private static func assign(_ jsonNames: [String], _ pictures: [PaprikaZipReader.Entry]) -> [String: PaprikaZipReader.Entry] {
        func directory(_ name: String) -> String {
            guard let slash = name.lastIndex(of: "/") else { return "" }
            return String(name[..<slash])
        }
        func isOriginal(_ entry: PaprikaZipReader.Entry) -> Bool {
            (entry.name.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? entry.name)
                .lowercased().hasPrefix("original")
        }
        var result: [String: PaprikaZipReader.Entry] = [:]
        for picture in pictures {
            let parent = directory(picture.name)
            let nearest = jsonNames
                .filter { !directory($0).isEmpty && (parent == directory($0) || parent.hasPrefix(directory($0) + "/")) }
                .max { directory($0).count < directory($1).count }
            guard let owner = nearest ?? (jsonNames.count == 1 ? jsonNames[0] : nil) else { continue }
            if let current = result[owner], isOriginal(current) || !isOriginal(picture) { continue }
            result[owner] = picture
        }
        return result
    }

    /// A picture's bytes, or nil if it is over the cap (declared or real) or can't be unpacked.
    private static func readPicture(_ zip: Data, _ entry: PaprikaZipReader.Entry, _ limits: MealieLimits) -> Data? {
        if Int(entry.size) > limits.maxPhotoBytes || Int(entry.compressedSize) > limits.maxPhotoBytes { return nil }
        guard let data = PaprikaZipReader.contents(of: entry, in: zip, max: limits.maxPhotoBytes), !data.isEmpty else { return nil }
        return data
    }
}
