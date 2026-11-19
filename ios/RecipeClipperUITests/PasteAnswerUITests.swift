import XCTest

/// An AI's answer pasted in the app (#372), end to end: "Paste the answer" in the Ask an AI sheet
/// and Home's "Paste a link", which takes any recipe text. The clipboard is the app's own copy
/// (`-uiTestPasteboard`, as the sent-list tests do, so no system paste prompt); the answer then
/// goes where a share of it would: a recipe opens the editor, a week opens the meal-plan review,
/// and nothing is saved before Save. Android's `PasteTextViewModelsTest` and the sheet tests.
final class PasteAnswerUITests: RecipeUITestCase {

    private func recipe(_ name: String, day: String? = nil) -> String {
        "{\"@context\": \"https://schema.org\", \"@type\": \"Recipe\", \"name\": \"\(name)\", "
            + (day.map { "\"day\": \"\($0)\", " } ?? "")
            + "\"recipeYield\": \"2 servings\", \"recipeIngredient\": [\"1 cup rice\", \"2 eggs\"], "
            + "\"recipeInstructions\": [{\"@type\": \"HowToStep\", \"text\": \"Cook.\"}]}"
    }

    private func launchWithClipboard(_ clip: String, flags: [String] = ["shareText", "askAi"]) {
        launch(.empty, flags: flags, extraArguments: ["-uiTestPasteboard", clip])
    }

    private func pasteTheAnswer() {
        require(app.buttons["home.askAi"], "the Ask an AI tile").tap()
        require(app.buttons["askAiPaste"], "Paste the answer").tap()
    }

    func testAJsonRecipeInTheClipboardOpensTheEditorAndIsSavedOnlyOnSave() {
        launchWithClipboard("Here you go:\n```json\n\(recipe("Lemon Pasta"))\n```".replacingOccurrences(of: "\n", with: "\\n"))

        pasteTheAnswer()

        require(text("Check the recipe"), "the review")
        let name = require(app.textFields["Name"], "the name field")
        XCTAssertEqual(name.value as? String, "Lemon Pasta")
        require(app.buttons["edit.save"], "Save").tap()

        require(bookmark, "the saved recipe")
        require(text("Lemon Pasta"), "its name")
        require(textContaining("1 cup rice"), "its ingredient")
    }

    func testAWeekOfRecipesInTheClipboardOpensTheMealPlanReview() {
        launchWithClipboard(
            "[" + recipe("Fried rice", day: "2026-11-23") + ",\\n" + recipe("Omelette", day: "2026-11-24") + "]",
            flags: ["shareText", "askAi", "mealPlanImport"]
        )

        pasteTheAnswer()

        require(app.descendants(matching: .any)["mealplan.count"], "the meal-plan review")
        require(text("Fried rice"), "the first recipe")
        require(text("Omelette"), "the second recipe")
        require(app.buttons["mealplan.save"], "Save")
    }

    func testAnEmptyClipboardSaysSoAndOpensNothing() {
        launchWithClipboard("")

        pasteTheAnswer()

        require(app.staticTexts["Nothing to paste. Copy the answer first."], "the empty-clipboard words")
        assertAbsent(text("Check the recipe"), "the review")
    }

    func testPasteALinkTakesARecipeWrittenOutAndOpensTheEditor() {
        launch(.empty, flags: ["shareText"])

        require(homePasteLink).tap()
        let field = require(app.descendants(matching: .any)["pasteField"], "the Paste a link sheet's field")
        field.tap()
        field.typeText("Ingredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix the flour and salt.\n2. Bake for 20 minutes.")
        require(app.buttons["pasteGo"], "Go").tap()

        require(text("Check the recipe"), "the review")
        require(textContaining("Sorted from the text you shared"), "how the split went")
        assertAbsent(bookmark, "a saved recipe: nothing is saved before Save")
    }

    func testPasteALinkStillImportsALink() {
        launch(.empty, flags: ["shareText"])

        require(homePasteLink).tap()
        let field = require(app.descendants(matching: .any)["pasteField"], "the field")
        field.tap()
        field.typeText("example.com/chicken")
        require(app.buttons["pasteGo"], "Go").tap()

        require(bookmark, "the imported recipe")
        require(text("Stub Chicken Soup"))
    }
}
