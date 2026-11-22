import Foundation

/// Step photos a page shows beside its steps but leaves out of its JSON-LD (#438): Giallo
/// Zafferano writes bare photo numbers into every step ("con il sale grosso 1 ;", "Mescolate di
/// tanto in tanto 2 .") and shows the photos they point at only in its HTML, each step a
/// `div.gz-content-recipe-step` holding `<picture data-step="1">` photos and the numbers as
/// `<span class="num-step">1</span>`. Read only when the page proves every number: the same count
/// of steps as JSON-LD's, each step's letters and digits found in its JSON-LD step, each step's
/// numbers exactly its photos' numbers, in order, and those numbers 1, 2, 3... through the recipe,
/// so the badges `StepPhotos.numbered` gives them (their place among the recipe's step photos) are
/// the numbers in the text. Anything else reads as no photos, and the text stays as written.
///
/// What a page's photo card is comes from `shared/tables/site-rules.json` (`SiteRules`), as
/// selectors. Pure. A port of Android's `PageStepPhotos`, pinned to it by the differential corpus.
enum PageStepPhotos {

    /// One kind of step-photo layout: `step` holds one step; `photo` is one of its photos (an
    /// `img`, or an element holding one) and `number` the photo's attribute giving its number;
    /// `marker` is the step text's element holding a number.
    struct Card {
        let step: CardSelector
        let photo: CardSelector
        let number: String
        let marker: CardSelector
    }

    /// Each of `steps`' photos from `html` (the page at `url`), lined up with them, or nil unless `card` proves them all.
    static func read(html: String, url: String, steps: [String], card: Card) -> [[String]]? {
        if steps.isEmpty || !card.step.needles.contains(where: { $0.isEmpty || html.contains($0) }) { return nil }
        let tree = HtmlTree(html)
        let all = card.step.select(tree, in: 0)
        let chosen = Set(all)
        // Only the outermost: a step inside another step is part of it.
        let containers = all.filter { e in
            var node = tree.elements[e].parent
            while let n = node {
                if chosen.contains(n) { return false }
                node = tree.elements[n].parent
            }
            return true
        }
        if containers.count != steps.count { return nil }
        var photos: [[String]] = []
        var expected = 1
        for (i, container) in containers.enumerated() {
            let key = CardHeadings.letters(steps[i])
            if key.isEmpty || !CardHeadings.letters(CardIngredients.text(tree, container)).contains(key) { return nil }
            let inside = Array(tree.descendants(of: container))
            let shown = inside.filter { card.photo.matches(tree.elements[$0]) }
            var numbers: [Int] = []
            for e in shown {
                guard let n = number((tree.elements[e].attributes[card.number] ?? "").kTrimmed) else { return nil }
                numbers.append(n)
            }
            var marks: [Int] = []
            for e in inside where card.marker.matches(tree.elements[e]) {
                guard let n = number(CardIngredients.text(tree, e)) else { return nil }
                marks.append(n)
            }
            if numbers != marks || shown.count > StepPhotos.maxPerStep { return nil }
            if numbers != Array(expected..<(expected + numbers.count)) { return nil }
            expected += numbers.count
            var links: [String] = []
            for e in shown {
                let img = tree.elements[e].name == "img" ? e : tree.descendants(of: e).first { tree.elements[$0].name == "img" }
                guard let img, let link = WebImageUrl.of(absolute(tree.elements[img].attributes["src"] ?? "", url)) else { return nil }
                links.append(link)
            }
            photos.append(links)
        }
        if expected == 1 || Set(photos.flatMap { $0 }).count != expected - 1 { return nil }
        return photos
    }

    /// A photo's number: one to three ASCII digits, nothing else.
    private static func number(_ s: String) -> Int? {
        guard (1...3).contains(s.count), s.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return Int(s)
    }

    /// Jsoup's `absUrl`: resolved against the page, empty when it can't be.
    private static func absolute(_ raw: String, _ base: String) -> String {
        guard !raw.isEmpty, let url = URL(string: raw, relativeTo: URL(string: base)) else { return "" }
        return url.absoluteString
    }
}
