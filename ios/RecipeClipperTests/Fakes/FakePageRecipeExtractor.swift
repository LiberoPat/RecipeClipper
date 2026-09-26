@testable import RecipeClipper

/// The on-device model reading a page (#103), faked: `picks` is what it "picks" from any text,
/// `languages` the languages it reads, with a window of `windowChars`. `asked` records every
/// text it was given. Android's `FakePageRecipeExtractor`.
final class FakePageRecipeExtractor: PageRecipeExtractor {
    var picks: PageSelection?
    var languages: Set<String>
    var chars: Int
    private(set) var asked: [String] = []

    init(picks: PageSelection? = nil, languages: Set<String> = ["en"], windowChars: Int = 6_000) {
        self.picks = picks
        self.languages = languages
        self.chars = windowChars
    }

    func windowChars(language: String) async -> Int? { languages.contains(language) ? chars : nil }

    func extract(_ text: String, language: String) async -> PageSelection? {
        asked.append(text)
        return picks
    }
}
