import Foundation

// "Clip it yourself" (#37): ported from Android's data/model/ClipSelection.kt and ClipDraft.kt.
// Pure: no WebKit, no UI.

/// How text selected on a web page becomes recipe fields. The page hands over
/// `window.getSelection().toString()`, which puts a line break between blocks; this splits it.
/// Nothing is guessed: a line is one item exactly as written.
enum ClipSelection {

    /// One item per line: split on line breaks, collapse runs of spaces, drop blank lines.
    static func lines(_ text: String) -> [String] {
        // Swift treats "\r\n" as one Character, so it splits once, like Kotlin's "\r\n" branch.
        text.split(omittingEmptySubsequences: false) { c in
            c == "\n" || c == "\r" || c == "\r\n" || c == "\u{2028}" || c == "\u{2029}" || c == "\u{85}"
        }
        .map { collapse(String($0)) }
        .filter { !$0.isEmpty }
    }

    /// A name is one line: the selection's lines joined with a space.
    static func name(_ text: String) -> String { lines(text).joined(separator: " ") }

    /// Every run of spaces (tabs and no-break spaces included) becomes one space, then trimmed.
    private static func collapse(_ line: String) -> String {
        line.split(whereSeparator: { $0.isWhitespace || $0 == "\u{A0}" || $0 == "\u{2007}" || $0 == "\u{202F}" })
            .joined(separator: " ")
    }
}

/// Where a selection on the page can go. The photo is picked by tapping an image instead.
enum ClipField: String, CaseIterable, Equatable {
    case name = "NAME"
    case ingredients = "INGREDIENTS"
    case steps = "STEPS"
    case photo = "PHOTO"
}

/// A recipe being clipped by hand from a page with no recipe data. A value type: every change
/// returns a new draft, so undo is "the draft before the last change". Assigning to a field
/// **replaces** what it held. `marks` records, per field, the id of the highlight the page drew
/// for that assignment; ids come from `nextMark`, so they never repeat within one draft.
/// `serves` and `totalTime` are only ever typed by the user in Review.
struct ClipDraft: Equatable {
    var sourceUrl: String
    var name: String = ""
    var ingredients: [String] = []
    var steps: [String] = []
    var photo: String? = nil
    var serves: String = ""
    var totalTime: String = ""
    var marks: [ClipField: String] = [:]
    var nextMark: Int = 1

    private static func blank(_ s: String) -> Bool { s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// True when nothing has been assigned or typed: not worth keeping as a draft.
    var isEmpty: Bool {
        Self.blank(name) && ingredients.allSatisfy(Self.blank) && steps.allSatisfy(Self.blank)
            && photo == nil && Self.blank(serves) && Self.blank(totalTime)
    }

    /// The parser's own rule: a name, plus ingredients or steps.
    var canFinish: Bool {
        !Self.blank(name) && (ingredients.contains { !Self.blank($0) } || steps.contains { !Self.blank($0) })
    }

    /// Lines a field holds, as counted on the toolbar: 1 for a name or a photo.
    func count(_ field: ClipField) -> Int {
        switch field {
        case .name: return Self.blank(name) ? 0 : 1
        case .ingredients: return ingredients.filter { !Self.blank($0) }.count
        case .steps: return steps.filter { !Self.blank($0) }.count
        case .photo: return photo == nil ? 0 : 1
        }
    }

    /// The id the next assignment's page mark will use.
    var pendingMarkId: String { "m\(nextMark)" }

    /// Puts the selected `text` into `field`, replacing what was there, and records the page
    /// mark under `pendingMarkId`. A selection with no text changes nothing (returns self).
    /// For `.photo`, `text` is the image's address.
    func assign(_ field: ClipField, _ text: String) -> ClipDraft {
        var draft = self
        switch field {
        case .name:
            let name = ClipSelection.name(text)
            if name.isEmpty { return self }
            draft.name = name
        case .ingredients:
            let lines = ClipSelection.lines(text)
            if lines.isEmpty { return self }
            draft.ingredients = lines
        case .steps:
            let lines = ClipSelection.lines(text)
            if lines.isEmpty { return self }
            draft.steps = lines
        case .photo:
            let src = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if src.isEmpty { return self }
            draft.photo = src
        }
        draft.marks[field] = pendingMarkId
        draft.nextMark += 1
        return draft
    }

    /// Empties `field` and drops its page mark.
    func clear(_ field: ClipField) -> ClipDraft {
        var draft = self
        switch field {
        case .name: draft.name = ""
        case .ingredients: draft.ingredients = []
        case .steps: draft.steps = []
        case .photo: draft.photo = nil
        }
        draft.marks[field] = nil
        return draft
    }

    // MARK: Review: editing lines by hand. Blank lines are kept while editing, dropped on save.

    func lines(_ field: ClipField) -> [String] {
        switch field {
        case .ingredients: return ingredients
        case .steps: return steps
        default: preconditionFailure("\(field) has no lines")
        }
    }

    private func with(_ field: ClipField, lines: [String]) -> ClipDraft {
        var draft = self
        switch field {
        case .ingredients: draft.ingredients = lines
        case .steps: draft.steps = lines
        default: preconditionFailure("\(field) has no lines")
        }
        return draft
    }

    /// Replaces line `index` of `field`; an index out of range changes nothing.
    func editLine(_ field: ClipField, _ index: Int, _ text: String) -> ClipDraft {
        var current = lines(field)
        guard current.indices.contains(index) else { return self }
        current[index] = text
        return with(field, lines: current)
    }

    func removeLine(_ field: ClipField, _ index: Int) -> ClipDraft {
        var current = lines(field)
        guard current.indices.contains(index) else { return self }
        current.remove(at: index)
        return with(field, lines: current)
    }

    /// Adds an empty line at the end, for the user to type into.
    func addLine(_ field: ClipField) -> ClipDraft { with(field, lines: lines(field) + [""]) }

    /// The recipe to save: trimmed, blank lines dropped, and a serving count or time only when
    /// one was typed. Nil until `canFinish`.
    func toRecipe() -> Recipe? {
        guard canFinish else { return nil }
        let trim = { (s: String) in s.trimmingCharacters(in: .whitespacesAndNewlines) }
        let serves = trim(self.serves)
        let totalTime = trim(self.totalTime)
        return Recipe(
            name: trim(name),
            image: photo,
            ingredients: ingredients.map(trim).filter { !$0.isEmpty },
            instructions: steps.map(trim).filter { !$0.isEmpty },
            prepTime: nil,
            cookTime: nil,
            totalTime: totalTime.isEmpty ? nil : totalTime,
            yield: serves.isEmpty ? nil : serves,
            sourceUrl: sourceUrl
        )
    }
}
