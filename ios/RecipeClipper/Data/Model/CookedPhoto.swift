import Foundation

/// One "I made this" entry (#116; Android's CookedPhoto): the user's own photo of a recipe they
/// cooked, the `day` they cooked it (a local epoch day, `PlanDays`) and an optional short note.
/// `path` is the stored JPEG, for showing and sharing it; it belongs to the recipe.
struct CookedPhoto: Equatable, Identifiable {
    let id: Int64
    let recipeId: Int64
    let fileName: String
    let path: String
    var day: Int64
    var note: String?
    let createdAt: Int64
    let updatedAt: Int64
    let uid: String

    /// The longest note kept: "short", a line or two under a photo.
    static let maxNote = 280

    /// A note as stored: trimmed, blank as none, at most `maxNote` characters.
    static func cleanNote(_ note: String?) -> String? {
        guard let note else { return nil }
        let trimmed = String(note.kTrimmed.prefix(maxNote))
        return trimmed.isEmpty ? nil : trimmed
    }
}
