@testable import RecipeClipper

/// The on-device model reading a page (#103), faked: `picks` is what it "picks" from any text,
/// `languages` the languages it reads, with a window of `windowChars`. Asked in two parts (#128):
/// `extract` answers `picks` without its steps and records the text in `asked`; `extractSteps`
/// answers its steps (none while `answersSteps` is false) and records the name in `askedSteps`.
/// Android's `FakePageRecipeExtractor`.
final class FakePageRecipeExtractor: PageRecipeExtractor {
    var picks: PageSelection?
    var languages: Set<String>
    var chars: Int
    var answersSteps = true
    private(set) var asked: [String] = []
    private(set) var askedSteps: [String] = []

    init(picks: PageSelection? = nil, languages: Set<String> = ["en"], windowChars: Int = 6_000) {
        self.picks = picks
        self.languages = languages
        self.chars = windowChars
    }

    func windowChars(language: String) async -> Int? { languages.contains(language) ? chars : nil }

    func extract(_ text: String, language: String) async -> PageSelection? {
        asked.append(text)
        var head = picks
        head?.steps = []
        return head
    }

    func extractSteps(_ text: String, language: String, name: String) async -> [String]? {
        askedSteps.append(name)
        return answersSteps ? picks?.steps : nil
    }
}
