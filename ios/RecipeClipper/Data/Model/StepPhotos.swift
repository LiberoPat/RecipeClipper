import Foundation

/// One photo of a step (#433), as the screens show it: `url` a web image, `step` the step's index,
/// `number` what its badge says (the step text's own "(n)" marker for it, else its place among the
/// recipe's step photos, 1-based) and `position` its place in the viewer (0-based, every step photo
/// of the recipe in step order).
struct StepPhoto: Equatable, Hashable {
    let url: String
    let step: Int
    let number: Int
    let position: Int
}

/// The photos a source gives each step (#433): Cookist ends its steps in "(1)" or "(3) (4)",
/// markers that point at the step photos in its JSON-LD (`HowToStep.image`). Stored as
/// `Recipe.stepPhotos`, one list of web image links per step, lined up with the steps; an empty
/// list for a recipe with none. The text is never touched: the markers stay as written, and a badge
/// on each thumbnail carries the same number. Pure; Android's `StepPhotos` is the same, pinned by
/// the differential corpus's `StepPh` rows.
enum StepPhotos {

    /// The most photos one step keeps: a page can't fill the database with one step's images.
    static let maxPerStep = 8

    // "(1)", "(13)": a bare number in brackets, as Cookist writes its photo markers. ASCII digits
    // only, as Java's `\d`.
    private static let marker = try! NSRegularExpression(pattern: "\\(([0-9]{1,2})\\)")

    /// The "(n)" markers in `step`, in order.
    static func markers(_ step: String) -> [Int] {
        let ns = step as NSString
        return marker.matches(in: step, range: NSRange(location: 0, length: ns.length))
            .compactMap { Int(ns.substring(with: $0.range(at: 1))) }
    }

    /// `photos` lined up with `stepCount` steps: one list per step (cut, or padded with empty
    /// lists), or an empty list when no step has a photo, so a recipe without any stores nothing.
    static func aligned(_ photos: [[String]], _ stepCount: Int) -> [[String]] {
        let lined = (0..<max(stepCount, 0)).map { $0 < photos.count ? photos[$0] : [] }
        return lined.allSatisfy { $0.isEmpty } ? [] : lined
    }

    /// Each step's photos as the screens show them. A step's markers number its photos when there
    /// are exactly as many markers as photos ("(3) (4)" for two); otherwise its photos are numbered
    /// by their place among the recipe's step photos.
    static func numbered(_ steps: [String], _ photos: [[String]]) -> [[StepPhoto]] {
        let lined = aligned(photos, steps.count)
        if lined.isEmpty { return [] }
        var position = 0
        return lined.enumerated().map { step, urls in
            let found = markers(steps[step])
            let marks = found.count == urls.count ? found : nil
            return urls.enumerated().map { i, url in
                defer { position += 1 }
                return StepPhoto(url: url, step: step, number: marks?[i] ?? (position + 1), position: position)
            }
        }
    }

    /// Every step photo of `numbered`, in step order: the viewer's pages.
    static func all(_ numbered: [[StepPhoto]]) -> [StepPhoto] { numbered.flatMap { $0 } }

    /// The photos after an edit (#29) changed `oldSteps` into `newSteps`. A step whose text is
    /// unchanged keeps its photos wherever it moved to; then the steps left over on each side are
    /// paired in order, but only when as many were left on both, so an edited step's text keeps its
    /// photos. Anything else leaves the unpaired new steps with none.
    static func afterEdit(_ oldSteps: [String], _ oldPhotos: [[String]], _ newSteps: [String]) -> [[String]] {
        let old = aligned(oldPhotos, oldSteps.count)
        if old.isEmpty { return [] }
        var taken = [Bool](repeating: false, count: oldSteps.count)
        var result = [[String]?](repeating: nil, count: newSteps.count)
        for (i, step) in newSteps.enumerated() {
            if let match = oldSteps.indices.first(where: { !taken[$0] && oldSteps[$0] == step }) {
                taken[match] = true
                result[i] = old[match]
            }
        }
        let leftOld = oldSteps.indices.filter { !taken[$0] }
        let leftNew = newSteps.indices.filter { result[$0] == nil }
        if leftOld.count == leftNew.count {
            for (n, o) in zip(leftNew, leftOld) { result[n] = old[o] }
        }
        return aligned(result.map { $0 ?? [] }, newSteps.count)
    }

    /// `photos` as stored: a JSON array of arrays of links, or nil when there are none.
    static func encode(_ photos: [[String]]) -> String? {
        if photos.allSatisfy({ $0.isEmpty }) { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: photos, options: [.withoutEscapingSlashes]) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// What `encode` wrote; anything unreadable (or nil) is no photos, never a crash. Each link is
    /// checked to be a web image again (`WebImageUrl`), so a stored or imported row can't name a
    /// file on the device.
    static func decode(_ json: String?) -> [[String]] {
        guard let json, !json.kTrimmed.isEmpty, let data = json.data(using: .utf8),
              let outer = try? JSONSerialization.jsonObject(with: data) as? [Any] else { return [] }
        return read(outer)
    }

    /// An already-parsed JSON array of arrays (an export file's `stepPhotos`), read as `decode` reads one.
    static func read(_ outer: [Any]) -> [[String]] {
        let steps: [[String]] = outer.map { element in
            guard let inner = element as? [Any] else { return [] }
            var seen = Set<String>()
            var links: [String] = []
            for item in inner {
                guard let link = WebImageUrl.of(item as? String), !seen.contains(link) else { continue }
                seen.insert(link)
                links.append(link)
                if links.count == maxPerStep { break }
            }
            return links
        }
        return steps.allSatisfy { $0.isEmpty } ? [] : steps
    }
}
