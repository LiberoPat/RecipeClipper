import Foundation

// "Clip it yourself" (#37) copy that needs the clip's own types. Kept out of Strings.swift,
// which the share extension also compiles without the UI/Clip folder. The words are in the
// String Catalog like every other string.
extension Strings {
    static func clipField(_ field: ClipField) -> String {
        switch field {
        case .name: return String(localized: "clip_field_name")
        case .ingredients: return String(localized: "clip_field_ingredients")
        case .steps: return String(localized: "clip_field_steps")
        case .photo: return String(localized: "clip_field_photo")
        }
    }
    /// Not words (Android's translatable="false" `clip_tag_count`).
    static func clipTagCount(_ label: String, _ n: Int) -> String { "\(label) · \(n)" }
    static func clipLinesSelected(_ n: Int) -> String {
        String(localized: "clip_lines_selected \(n)") + " · " + String(localized: "clip_lines_selected_hint")
    }
    static func clipSummary(_ draft: ClipDraft) -> String {
        [
            draft.count(.name) > 0 ? String(localized: "clip_summary_name") : String(localized: "clip_summary_no_name"),
            String(localized: "clip_summary_ingredients \(draft.count(.ingredients))"),
            String(localized: "clip_summary_steps \(draft.count(.steps))"),
            draft.photo != nil ? String(localized: "clip_summary_photo") : String(localized: "clip_summary_no_photo"),
        ].joined(separator: " · ")
    }
    static func clipMessage(_ message: ClipMessage) -> String {
        switch message {
        case .assigned(.name, _): return String(localized: "clip_added_name")
        case .assigned(.ingredients, let n): return String(localized: "clip_added_ingredients \(n)")
        case .assigned(.steps, let n): return String(localized: "clip_added_steps \(n)")
        case .assigned(.photo, _): return String(localized: "clip_added_photo")
        case .cleared(.name): return String(localized: "clip_cleared_name")
        case .cleared(.ingredients): return String(localized: "clip_cleared_ingredients")
        case .cleared(.steps): return String(localized: "clip_cleared_steps")
        case .cleared(.photo): return String(localized: "clip_cleared_photo")
        case .draftRestored: return String(localized: "clip_draft_restored")
        case .saveFailed: return String(localized: "clip_save_failed")
        case .unlock(let outcome): return unlockNotice(outcome)
        }
    }
}
