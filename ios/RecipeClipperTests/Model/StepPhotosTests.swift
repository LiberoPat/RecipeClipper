import XCTest
@testable import RecipeClipper

/// `StepPhotos` (#433; Android's StepPhotosTest and StepPhotosParserTest): numbering by the "(n)"
/// markers, lining up, an edit, storage, and the JSON-LD parser's `HowToStep.image`.
final class StepPhotosTests: XCTestCase {
    private let a = "https://img.example/a.jpg"
    private let b = "https://img.example/b.jpg"
    private let c = "https://img.example/c.jpg"
    private let d = "https://img.example/d.jpg"

    // MARK: StepPhotos

    func testMarkersAreBareNumbersInBracketsInOrder() {
        XCTAssertEqual(StepPhotos.markers("Sbuccia le mele (3) (4)."), [3, 4])
        XCTAssertEqual(StepPhotos.markers("Ripiega sul ripieno (5), in modo da formare un fagottino."), [5])
        XCTAssertEqual(StepPhotos.markers("Bake (about 20 minutes) at 180 °C (350 °F)."), [])
        XCTAssertEqual(StepPhotos.markers("Use (123) or ( 4 )."), [])
    }

    func testMarkersNumberAStepsPhotosWhenThereAreAsManyElseTheirPlaceDoes() {
        let steps = ["Mix (1).", "Peel (3) (4).", "Heat the oven.", "Bake (9).", "Serve."]
        let photos = [[a], [b, c], [], [d], [a, b]]
        let numbered = StepPhotos.numbered(steps, photos)
        XCTAssertEqual(numbered.map { $0.map(\.number) }, [[1], [3, 4], [], [9], [5, 6]])
        XCTAssertEqual(StepPhotos.all(numbered).map(\.position), [0, 1, 2, 3, 4, 5])
        XCTAssertEqual(StepPhotos.all(numbered).map(\.step), [0, 1, 1, 3, 4, 4])
    }

    func testAStepWithTwoPhotosAndOneMarkerIsNumberedByPlace() {
        let numbered = StepPhotos.numbered(["Peel (3).", "Bake."], [[a, b], [c]])
        XCTAssertEqual(StepPhotos.all(numbered).map(\.number), [1, 2, 3])
    }

    func testNoPhotosNumberNothingAndAShortListIsLinedUp() {
        XCTAssertEqual(StepPhotos.numbered(["Mix."], []), [])
        XCTAssertEqual(StepPhotos.numbered(["Mix."], [[]]), [])
        let numbered = StepPhotos.numbered(["Mix (1).", "Bake."], [[a]])
        XCTAssertEqual(numbered.count, 2)
        XCTAssertEqual(numbered[1], [])
        XCTAssertEqual(StepPhotos.aligned([[a]], 2), [[a], []])
        XCTAssertEqual(StepPhotos.aligned([[a], [b]], 1), [[a]])
        XCTAssertEqual(StepPhotos.aligned([[], [b]], 1), [])
    }

    func testAnEditedStepKeepsItsPhotosAMovedOneCarriesThemARemovedOneLosesThem() {
        let old = ["Mix (1).", "Peel (2).", "Bake (3)."]
        let photos = [[a], [b], [c]]
        XCTAssertEqual(StepPhotos.afterEdit(old, photos, ["Mix well (1).", "Peel (2).", "Bake (3)."]), photos)
        XCTAssertEqual(StepPhotos.afterEdit(old, photos, ["Bake (3).", "Mix (1).", "Peel (2)."]), [[c], [a], [b]])
        XCTAssertEqual(StepPhotos.afterEdit(old, photos, ["Mix (1).", "Bake (3)."]), [[a], [c]])
        XCTAssertEqual(StepPhotos.afterEdit(old, photos, ["Mix (1).", "Rest.", "Peel (2).", "Bake (3)."]), [[a], [], [b], [c]])
        XCTAssertEqual(StepPhotos.afterEdit(old, photos, ["Mix (1).", "Rest.", "Peel well.", "Bake (3)."]), [[a], [], [], [c]])
        XCTAssertEqual(StepPhotos.afterEdit(old, photos, ["Something else."]), [])
        XCTAssertEqual(StepPhotos.afterEdit(old, [], ["Mix (1)."]), [])
    }

    func testAnEditThroughTheDraftKeepsThePhotos() {
        var recipe = Recipe(
            name: "Pie", image: nil, ingredients: ["1 egg"], instructions: ["Mix (1).", "Bake (2)."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://x.example/pie"
        )
        recipe.stepPhotos = [[a], [b]]
        var draft = RecipeDraft.of(recipe)
        draft.instructionsText = "Bake (2).\nMix it all (1)."
        XCTAssertEqual(draft.apply(to: recipe).stepPhotos, [[b], [a]])
    }

    func testStoredAsJsonReadBackAndAnythingOddReadsAsNone() {
        let photos = [[a], [], [b, c]]
        XCTAssertEqual(StepPhotos.decode(StepPhotos.encode(photos)), photos)
        XCTAssertNil(StepPhotos.encode([]))
        XCTAssertNil(StepPhotos.encode([[], []]))
        XCTAssertEqual(StepPhotos.decode(nil), [])
        XCTAssertEqual(StepPhotos.decode(""), [])
        XCTAssertEqual(StepPhotos.decode("not json"), [])
        XCTAssertEqual(StepPhotos.decode("{\"a\":1}"), [])
        XCTAssertEqual(StepPhotos.decode("[[]]"), [])
        // A link that isn't a web image is dropped as it is read.
        XCTAssertEqual(StepPhotos.decode("[[\"file:///x.jpg\",\"\(a)\"],[1]]"), [[a], []])
    }

    // MARK: The JSON-LD parser

    private func parse(_ steps: String) -> Recipe? {
        JsonLdRecipeParser.parse(
            [#"{"@type":"Recipe","name":"Pie","recipeIngredient":["1 egg"],"recipeInstructions":"# + steps + "}"],
            sourceUrl: "https://example.com/recipe"
        )
    }

    func testCookistGivesEachStepItsPhotoAndTheMarkersNumberThem() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "cookist-fagottini-di-mortadella", withExtension: "json", subdirectory: "fixtures/jsonld"
        ))
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse(
            [try String(contentsOf: url, encoding: .utf8)], sourceUrl: "https://www.cookist.it/fagottini-di-mortadella-ricetta/"
        ))
        XCTAssertEqual(recipe.instructions.count, 8)
        XCTAssertEqual(recipe.stepPhotos.count, 8)
        XCTAssertTrue(recipe.stepPhotos.allSatisfy { $0.count == 1 })
        XCTAssertEqual(
            recipe.stepPhotos[0][0],
            "https://staticcookist.akamaized.net/wp-content/uploads/sites/21/2024/01/Fagottini-di-mortadella_farcire_mortadella_1-1200x675.jpg"
        )
        // The markers stay in the text as written.
        XCTAssertEqual(recipe.instructions[0], "Stendi le fette di mortadella su un tagliere (1).")
        let numbered = StepPhotos.all(StepPhotos.numbered(recipe.instructions, recipe.stepPhotos))
        XCTAssertEqual(numbered.map(\.number), Array(1...8))
        XCTAssertEqual(numbered.map(\.position), Array(0...7))
    }

    func testAStringAnImageObjectAndAnArrayOfEitherAreAllRead() throws {
        let recipe = try XCTUnwrap(parse(#"""
            [
              {"@type":"HowToStep","text":"One (1).","image":"https://img.example/1.jpg"},
              {"@type":"HowToStep","text":"Two (2) (3).","image":[{"@type":"ImageObject","url":"https://img.example/2.jpg"},"https://img.example/3.jpg"]},
              {"@type":"HowToStep","text":"Three.","image":{"@type":"ImageObject","contentUrl":"http://img.example/4.jpg"}},
              {"@type":"HowToStep","text":"Four."}
            ]
            """#))
        XCTAssertEqual(recipe.stepPhotos, [
            ["https://img.example/1.jpg"],
            ["https://img.example/2.jpg", "https://img.example/3.jpg"],
            ["https://img.example/4.jpg"],
            [],
        ])
    }

    func testNoStepPhotoIsNoListAtAll() {
        XCTAssertEqual(parse(#"[{"@type":"HowToStep","text":"Mix."},"Bake."]"#)?.stepPhotos, [])
        XCTAssertEqual(parse(#""Mix.\nBake.""#)?.stepPhotos, [])
    }

    func testOnlyWebImagesEachOnceAtMostEightAStep() throws {
        let many = (1...12).map { "\"https://img.example/\($0).jpg\"" }.joined(separator: ",")
        let recipe = try XCTUnwrap(parse(#"""
            [
              {"@type":"HowToStep","text":"One.","image":["file:///data/x.jpg","data:image/gif;base64,AA","/rel.jpg","https://img.example/a.jpg","https://img.example/a.jpg"]},
              {"@type":"HowToStep","text":"Two.","image":[\#(many)]}
            ]
            """#))
        XCTAssertEqual(recipe.stepPhotos[0], ["https://img.example/a.jpg"])
        XCTAssertEqual(recipe.stepPhotos[1].count, StepPhotos.maxPerStep)
    }

    func testSectionsAreFlattenedWithTheirStepsPhotos() throws {
        let recipe = try XCTUnwrap(parse(#"""
            [
              {"@type":"HowToSection","name":"Dough","itemListElement":[
                {"@type":"HowToStep","text":"Knead.","image":"https://img.example/k.jpg"},
                "Rest."
              ]},
              {"@type":"HowToSection","name":"Filling","itemListElement":[
                {"@type":"HowToStep","text":"Fill.","image":"https://img.example/f.jpg"}
              ]}
            ]
            """#))
        XCTAssertEqual(recipe.instructions, ["Knead.", "Rest.", "Fill."])
        XCTAssertEqual(recipe.stepPhotos, [["https://img.example/k.jpg"], [], ["https://img.example/f.jpg"]])
    }

    func testABlankStepIsDroppedWithItsPhoto() throws {
        let recipe = try XCTUnwrap(parse(#"""
            [
              {"@type":"HowToStep","text":" ","image":"https://img.example/blank.jpg"},
              {"@type":"HowToStep","text":"Bake.","image":"https://img.example/bake.jpg"}
            ]
            """#))
        XCTAssertEqual(recipe.instructions, ["Bake."])
        XCTAssertEqual(recipe.stepPhotos, [["https://img.example/bake.jpg"]])
    }

    func testASiteRuleThatDropsTheLastStepDropsItsPhotosToo() throws {
        let html = #"""
            <html><head><script type="application/ld+json">
            {"@type":"Recipe","name":"Pie","recipeIngredient":["1 egg"],"recipeInstructions":[
              {"@type":"HowToStep","text":"Bake.","image":"https://img.example/1.jpg"},
              {"@type":"HowToStep","text":"Rest.","image":"https://img.example/2.jpg"},
              {"@type":"HowToStep","text":"Editor’s note: first published in 2019.","image":"https://img.example/3.jpg"}
            ]}
            </script></head><body></body></html>
            """#
        guard case .success(let recipe) = BlogRecipeSource.parse(html: html, url: "https://www.bonappetit.com/recipe/pie") else {
            return XCTFail("no recipe")
        }
        XCTAssertEqual(recipe.instructions, ["Bake.", "Rest."])
        XCTAssertEqual(recipe.stepPhotos, [["https://img.example/1.jpg"], ["https://img.example/2.jpg"]])
    }
}
