import Foundation

/// A recipe's own picture stored on the phone (#386; Android's `StoredPhoto`), as the screens name it
/// to the image loader: `photo://<file name>`, the name of a file in the photo store, optionally
/// followed by `#` and the recipe's web picture link, which the loader shows instead when the file
/// isn't there (a cleared store, a phone restored without it): never a blank where the link would have
/// shown. A web picture alone is still its plain link. One string, so every place that shows a
/// recipe's picture passes the same thing whichever it is, and `ImageLoader` turns it into the file or
/// the link. The list queries build the same string in SQL (`RecipeImageSql`). Pure.
enum StoredPhoto {
    static let scheme = "photo://"
    private static let fallbackMark: Character = "#"

    /// The model for `fileName`, falling back to `fallback` (a web link; its fragment is dropped, it never matters to a picture).
    static func model(_ fileName: String, fallback: String? = nil) -> String {
        let link = fallback.map { String($0.prefix { $0 != fallbackMark }) }.flatMap { $0.kIsBlank ? nil : $0 }
        return scheme + fileName + (link.map { "\(fallbackMark)\($0)" } ?? "")
    }

    /// The file name `model` names, or nil if it is a web link or anything else. A name is a plain
    /// name: one with a path in it names no file.
    static func fileName(_ model: String) -> String? {
        guard model.hasPrefix(scheme) else { return nil }
        let name = String(model.dropFirst(scheme.count).prefix { $0 != fallbackMark })
        guard !name.isEmpty, !name.contains("/"), !name.contains("\\"), name != "..", name != "." else { return nil }
        return name
    }

    /// The web link `model` falls back to when its file is missing, or nil for none.
    static func fallback(_ model: String) -> String? {
        guard fileName(model) != nil, let mark = model.firstIndex(of: fallbackMark) else { return nil }
        let link = String(model[model.index(after: mark)...])
        return link.isEmpty ? nil : link
    }
}

/// The recipe picture the list queries return as `imageUrl` (#386; Android's `RecipeImageSql`):
/// `photo://<photoFile>` followed by `#` and the recipe's web link when it has both (so the loader can
/// show the link when the file is missing), else the plain link. The same string as `StoredPhoto.model`:
/// the link's own fragment is cut (it never matters to a picture, and a second `#` would end it early).
enum RecipeImageSql {
    /// For a query over `recipes` with no alias.
    static let plain = """
        CASE WHEN photoFile IS NULL THEN imageUrl ELSE 'photo://' || photoFile || \
        COALESCE('#' || NULLIF(substr(imageUrl, 1, instr(imageUrl || '#', '#') - 1), ''), '') END
        """

    /// For a query over `recipes r`.
    static let asR = """
        CASE WHEN r.photoFile IS NULL THEN r.imageUrl ELSE 'photo://' || r.photoFile || \
        COALESCE('#' || NULLIF(substr(r.imageUrl, 1, instr(r.imageUrl || '#', '#') - 1), ''), '') END
        """
}
