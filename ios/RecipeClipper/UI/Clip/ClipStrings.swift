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
    /// What a field button shows it holds (#237): a check, with the count for ingredients and
    /// steps. Not words (Android's translatable="false" `clip_filled` and `clip_filled_count`).
    static func clipFilled(_ field: ClipField, _ n: Int) -> String {
        n == 0 ? "" : field.replaces ? "✓" : "✓ \(n)"
    }
    static func clipLinesSelected(_ n: Int) -> String {
        String(localized: "clip_lines_selected \(n)") + " · " + String(localized: "clip_selected_hint")
    }
    // Field first (#237): the hint bar.
    static func clipSelect(_ field: ClipField) -> String {
        switch field {
        case .name: return String(localized: "clip_select_name")
        case .ingredients: return String(localized: "clip_select_ingredients")
        case .steps, .photo: return String(localized: "clip_select_steps")
        }
    }
    static func clipConfirm(_ field: ClipField, _ n: Int) -> String {
        switch field {
        case .name: return String(localized: "clip_confirm_name")
        case .ingredients: return String(localized: "clip_confirm_ingredients \(n)")
        case .steps, .photo: return String(localized: "clip_confirm_steps \(n)")
        }
    }
    static func clipAdded(_ added: ClipAdded) -> String {
        let words: String
        switch added.field {
        case .name: words = String(localized: "clip_added_name")
        case .ingredients: words = String(localized: "clip_added_ingredients \(added.count)")
        case .steps: words = String(localized: "clip_added_steps \(added.count)")
        case .photo: words = String(localized: "clip_added_photo")
        }
        return words + "."
    }
    /// The field to fill next; `empty` (nothing in the draft yet) starts with how it works.
    static func clipNext(_ next: ClipField?, empty: Bool) -> String {
        switch next {
        case .name: return empty ? String(localized: "clip_hint_start") : String(localized: "clip_next_name")
        case .ingredients: return String(localized: "clip_next_ingredients")
        case .steps: return String(localized: "clip_next_steps")
        case .photo: return String(localized: "clip_next_photo")
        case nil: return String(localized: "clip_ready")
        }
    }
    static var clipShowText: String { String(localized: "clip_show_text") }
    static var clipShowPage: String { String(localized: "clip_show_page") }
    static var clipTextComments: String { String(localized: "clip_text_comments") }
    static var clipTextLoadedNote: String { String(localized: "clip_text_loaded_note") }
    /// Not words (Android's translatable="false" `clip_text_author`).
    static func clipTextAuthor(_ name: String) -> String { "u/\(name)" }
    static func clipMessage(_ message: ClipMessage) -> String {
        switch message {
        case .removed(.name, _): return String(localized: "clip_cleared_name")
        case .removed(.ingredients, let n): return String(localized: "clip_removed_ingredients \(n)")
        case .removed(.steps, let n): return String(localized: "clip_removed_steps \(n)")
        case .removed(.photo, _): return String(localized: "clip_cleared_photo")
        case .draftRestored: return String(localized: "clip_draft_restored")
        case .saveFailed: return String(localized: "clip_save_failed")
        case .photoUnreadable: return String(localized: "clip_photo_unreadable")
        case .unlock(let outcome): return unlockNotice(outcome)
        case .missing(name: true, lines: true): return String(localized: "clip_needs_name_and_lines")
        case .missing(name: true, lines: false): return String(localized: "clip_needs_name")
        case .missing: return String(localized: "clip_needs_lines")
        case .textUnreadable: return String(localized: "clip_text_unreadable")
        }
    }
}
