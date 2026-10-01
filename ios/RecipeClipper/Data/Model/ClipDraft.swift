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

    /// A name or a photo is replaced by each assignment; ingredients and steps add up (#237).
    var replaces: Bool { self == .name || self == .photo }
}

/// One add to a `ClipDraft`, and the page mark drawn for it (#37, #237): `id` names the mark,
/// `lines` are what it put into `field` (a name, or a photo's address, as its one line), so
/// removing it takes out exactly those lines.
struct ClipMark: Equatable {
    let id: String
    let field: ClipField
    let lines: [String]
}

/// A recipe being clipped by hand from a page with no recipe data (Android's ClipDraft). A value
/// type: every change returns a new draft, so undo is "the draft before the last change". The
/// name and the photo are **replaced** by each assignment; ingredients and steps are **added**
/// after what the field holds, so separate blocks of a page add up (the owner's call, #237).
/// `marks` records each add with the id of the highlight the page drew for it; ids come from
/// `nextMark`, so they never repeat within one draft. `serves` and `totalTime` are only ever typed
/// by the user in Review.
struct ClipDraft: Equatable {
    var sourceUrl: String
    var name: String = ""
    var ingredients: [String] = []
    var steps: [String] = []
    var photo: String? = nil
    var serves: String = ""
    var totalTime: String = ""
    var marks: [ClipMark] = []
    var nextMark: Int = 1

    private static func blank(_ s: String) -> Bool { s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// True when nothing has been assigned or typed: not worth keeping as a draft.
    var isEmpty: Bool {
        Self.blank(name) && ingredients.allSatisfy(Self.blank) && steps.allSatisfy(Self.blank)
            && photo == nil && Self.blank(serves) && Self.blank(totalTime)
    }

    /// Some ingredients or steps.
    var hasLines: Bool { ingredients.contains { !Self.blank($0) } || steps.contains { !Self.blank($0) } }

    /// The parser's own rule: a name, plus ingredients or steps.
    var canFinish: Bool { !Self.blank(name) && hasLines }

    /// Lines a field holds, as counted on the toolbar: 1 for a name or a photo.
    func count(_ field: ClipField) -> Int {
        switch field {
        case .name: return Self.blank(name) ? 0 : 1
        case .ingredients: return ingredients.filter { !Self.blank($0) }.count
        case .steps: return steps.filter { !Self.blank($0) }.count
        case .photo: return photo == nil ? 0 : 1
        }
    }

    /// The field the cook is pointed to next (#237): the first of name, ingredients, steps and
    /// photo still empty, or nil when every one holds something.
    var nextField: ClipField? { ClipField.allCases.first { count($0) == 0 } }

    /// The id the next assignment's page mark will use.
    var pendingMarkId: String { "m\(nextMark)" }

    /// Puts the selected `text` into `field` and records the page mark under `pendingMarkId`: a
    /// name or a photo replaces what was there (and its mark); ingredients and steps are added
    /// after what the field holds. A selection with no text changes nothing (returns self). For
    /// `.photo`, `text` is the image's address.
    func assign(_ field: ClipField, _ text: String) -> ClipDraft {
        let lines: [String]
        switch field {
        case .name:
            let name = ClipSelection.name(text)
            lines = name.isEmpty ? [] : [name]
        case .ingredients, .steps:
            lines = ClipSelection.lines(text)
        case .photo:
            let src = text.trimmingCharacters(in: .whitespacesAndNewlines)
            lines = src.isEmpty ? [] : [src]
        }
        guard !lines.isEmpty else { return self }
        var draft = self
        switch field {
        case .name: draft.name = lines[0]
        case .ingredients: draft.ingredients += lines
        case .steps: draft.steps += lines
        case .photo: draft.photo = lines[0]
        }
        if field.replaces { draft.marks.removeAll { $0.field == field } }
        draft.marks.append(ClipMark(id: pendingMarkId, field: field, lines: lines))
        draft.nextMark += 1
        return draft
    }

    /// The add a page mark stands for, while this draft still holds it.
    func mark(_ id: String) -> ClipMark? { marks.first { $0.id == id } }

    /// Takes back one add: a name or a photo empties its field; ingredients or steps lose the
    /// lines that add put there (for each, the first line still equal to it, so a line edited by
    /// hand in Review since then stays). An unknown id changes nothing.
    func removeMark(_ id: String) -> ClipDraft {
        guard let mark = mark(id) else { return self }
        var draft: ClipDraft
        switch mark.field {
        case .name, .photo:
            draft = clear(mark.field)
        case .ingredients, .steps:
            var left = lines(mark.field)
            for line in mark.lines {
                if let index = left.firstIndex(of: line) { left.remove(at: index) }
            }
            draft = with(mark.field, lines: left)
        }
        draft.marks = marks.filter { $0.id != id }
        return draft
    }

    /// Empties `field` and drops its page marks.
    func clear(_ field: ClipField) -> ClipDraft {
        var draft = self
        switch field {
        case .name: draft.name = ""
        case .ingredients: draft.ingredients = []
        case .steps: draft.steps = []
        case .photo: draft.photo = nil
        }
        draft.marks.removeAll { $0.field == field }
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
