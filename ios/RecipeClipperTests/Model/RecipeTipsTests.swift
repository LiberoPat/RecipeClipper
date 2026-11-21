import XCTest
@testable import RecipeClipper

/// HowToTip entries (#432; Android's JsonLdRecipeParserTest, "HowToTip"): the recipe's tips, kept
/// apart from the steps, in order and as written.
final class JsonLdTipsTests: XCTestCase {
    private let sourceUrl = "https://example.com/recipe"

    private func parse(_ block: String) -> Recipe? { JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl) }

    func testAHowToTipIsATipKeptApartFromTheStepsInOrderAndAsWritten() throws {
        let recipe = try XCTUnwrap(parse("""
            {"@type": "Recipe", "name": "R", "recipeIngredient": ["1 apple"], "recipeInstructions": [
              {"@type": "HowToStep", "text": "Peel."},
              {"@type": "HowToTip", "text": "Use <b>tart</b> apples &amp; a sharp knife."},
              {"@type": "HowToStep", "text": "Bake."},
              {"@type": "howtotip", "name": "A tip named, with no text."},
              {"@type": "HowToTip", "text": "  "}
            ]}
            """))
        XCTAssertEqual(recipe.instructions, ["Peel.", "Bake."])
        XCTAssertEqual(recipe.tips, ["Use tart apples & a sharp knife.", "A tip named, with no text."])
    }

    func testAHowToTipInsideAHowToSectionIsATipToo() throws {
        let recipe = try XCTUnwrap(parse("""
            {"@type": "Recipe", "name": "R", "recipeInstructions": [
              {"@type": "HowToSection", "name": "Crust", "itemListElement": [
                {"@type": "HowToStep", "text": "Roll."},
                {"@type": "HowToTip", "text": "Chill the dough if it gets soft."}
              ]},
              {"@type": "HowToSection", "name": "Filling", "itemListElement": [{"@type": "HowToStep", "text": "Stir."}]}
            ]}
            """))
        XCTAssertEqual(recipe.instructions, ["Roll.", "Stir."])
        XCTAssertEqual(recipe.tips, ["Chill the dough if it gets soft."])
    }

    func testInstructionsThatAreOnlyTipsAreNoStepsSoARecipeStillNeedsIngredients() throws {
        XCTAssertNil(parse(#"{"@type": "Recipe", "name": "R", "recipeInstructions": [{"@type": "HowToTip", "text": "A tip."}]}"#))
        let recipe = try XCTUnwrap(parse(
            #"{"@type": "Recipe", "name": "R", "recipeIngredient": ["1 egg"], "recipeInstructions": [{"@type": "HowToTip", "text": "A tip."}]}"#
        ))
        XCTAssertEqual(recipe.instructions, [])
        XCTAssertEqual(recipe.tips, ["A tip."])
    }

    func testARecipeWithNoHowToTipHasNoTipsAndItsTipsAsStepsReadAsBefore() throws {
        let recipe = try XCTUnwrap(parse(#"{"@type": "Recipe", "name": "R", "recipeInstructions": ["One.", "Two."]}"#))
        XCTAssertEqual(recipe.tips, [])
        XCTAssertEqual(recipe.withTipsAsSteps(), recipe)
        var tipped = recipe
        tipped.tips = ["Tip."]
        XCTAssertEqual(tipped.withTipsAsSteps().instructions, ["One.", "Two.", "Tip."])
        XCTAssertEqual(tipped.withTipsAsSteps().tips, [])
    }
}

/// kingarthurbaking.com's tips after the method (#432; Android's KingArthurPageTest): seventeen
/// HowToSteps, then three HowToTips, which are the recipe's tips, never steps 18 to 20. The page is the
/// saved page's one JSON-LD block, verbatim.
final class KingArthurPageTests: XCTestCase {
    func testKingArthursHowToTipEntriesAreTipsApartFromTheSeventeenSteps() throws {
        let html = try pageFixture("site-kingarthur-mini-apple-pies")
        guard case .success(let recipe) = BlogRecipeSource.parse(
            html: html, url: "https://www.kingarthurbaking.com/recipes/mini-apple-pies-recipe"
        ) else {
            return XCTFail("no recipe in the King Arthur page")
        }
        XCTAssertEqual(recipe.name, "Mini Apple Pies")
        XCTAssertEqual(recipe.ingredients.count, 9)
        XCTAssertEqual(recipe.instructions.count, 17)
        XCTAssertEqual(
            recipe.instructions.last,
            "Storage information: Store any leftover Mini Apple Pies, covered, at room temperature for up to 4 days; "
                + "freeze for longer storage."
        )
        XCTAssertEqual(recipe.tips.count, 3)
        XCTAssertTrue(recipe.tips[0].hasPrefix("You can skip the double-crust or lattice and make a crumb topping instead."))
        XCTAssertEqual(
            recipe.tips[1],
            "If you don’t have Apple Pie Spice , mix cinnamon, nutmeg, and allspice to taste and use 1 teaspoon of "
                + "that mixture in the filling."
        )
        XCTAssertTrue(recipe.tips[2].hasPrefix("Baking the Mini Apple Pies on a baking stone or steel"))
        XCTAssertEqual(recipe.prepTime, "1h 15m")
        XCTAssertEqual(recipe.yield, "24")
        XCTAssertEqual(recipe.language, "en")
    }
}

/// A recipe's tips (#432) wherever a recipe is carried (Android's RecipeTipsTest): shared as text and
/// printed after the steps, as written and unnumbered; written as HowToTip in the recipe JSON an AI is
/// asked to change, and read back as tips; kept by the editor's draft; carried by the backup file.
final class RecipeTipsTests: XCTestCase {
    private var pies: Recipe {
        var recipe = Recipe(
            name: "Mini Apple Pies", image: nil, ingredients: ["1 apple"], instructions: ["Bake.", "Cool."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://example.com/pies"
        )
        recipe.tips = ["Use a baking stone.", "Bake at 350°F if you have no stone."]
        return recipe
    }

    func testSharedTextPutsTheTipsAfterTheStepsUnderTheirHeadingUnnumbered() {
        let text = RecipeShareText.format(recipe: pies, servings: nil, ingredients: pies.ingredients, instructions: pies.instructions)
        XCTAssertEqual(
            text,
            "Mini Apple Pies\n\nINGREDIENTS\n1 apple\n\nINSTRUCTIONS\n1. Bake.\n2. Cool.\n\n"
                + "TIPS\nUse a baking stone.\nBake at 350°F if you have no stone."
        )
        var none = pies
        none.tips = []
        XCTAssertFalse(RecipeShareText.format(recipe: none, servings: nil, ingredients: none.ingredients, instructions: none.instructions)
            .contains("TIPS"))
    }

    func testThePrintedCardHasATipsSectionAfterTheStepsAndBeforeTheNote() {
        let blocks = RecipePrint.blocks(
            recipe: pies, servings: nil, ingredients: pies.ingredients, instructions: pies.instructions,
            sourceDomain: nil, note: "Mine"
        )
        XCTAssertEqual(Array(blocks.suffix(6)), [
            .step(2, "Cool."), .section("Tips"), .tip("Use a baking stone."), .tip("Bake at 350°F if you have no stone."),
            .section("Notes"), .note("Mine"),
        ])
    }

    func testTheRecipeJsonForAnAiWritesTheTipsAsHowToTipAfterTheStepsAndReadsThemBackAsTips() {
        let json = AskAiPrompt.recipeJson(pies)
        XCTAssertTrue(json.contains(
            "    {\"@type\": \"HowToStep\", \"text\": \"Cool.\"},\n    {\"@type\": \"HowToTip\", \"text\": \"Use a baking stone.\"}"
        ))
        let read = SharedText.read("```json\n\(json)\n```").recipe
        XCTAssertEqual(read.instructions, ["Bake.", "Cool."])
        XCTAssertEqual(read.tips, pies.tips)
        XCTAssertEqual(read.withTipsAsSteps().instructions, ["Bake.", "Cool."] + pies.tips)
        var none = pies
        none.tips = []
        XCTAssertFalse(AskAiPrompt.recipeJson(none).contains("HowToTip"))
    }

    func testTheEditorsDraftCarriesTheTipsOnePerLineAndABlankBoxIsNone() {
        let draft = RecipeDraft.of(pies)
        XCTAssertEqual(draft.tipsText, "Use a baking stone.\nBake at 350°F if you have no stone.")
        XCTAssertEqual(draft.apply(to: pies).tips, pies.tips)
        var blank = draft
        blank.tipsText = " \n "
        XCTAssertEqual(blank.apply(to: pies).tips, [])
        // Tips are never what makes a recipe: a name, plus ingredients or steps.
        XCTAssertFalse(RecipeDraft(name: "R", tipsText: "A tip.").isValid)
    }

    /// The shared fixture, read the same way by Android's BackupJsonTest.
    func testARecipesTipsReadRoundTripAreWrittenOnlyWhenThereAreSomeAndOlderFilesHaveNone() throws {
        let backup = try decodeOrFail(backupFixture("backup-v1-tips"))
        XCTAssertEqual(backup.recipes.map(\.tips), [
            ["If you don’t have Apple Pie Spice , mix cinnamon & nutmeg.", "Bake on a stone."], [],
        ])
        XCTAssertEqual(backup.recipes[0].instructions, ["Bake for 35 to 40 minutes.", "Cool."])
        XCTAssertEqual(backup, try decodeOrFail(BackupJson.encode(backup)))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(BackupJson.encode(backup).utf8)) as? [String: Any])
        let recipes = try XCTUnwrap(root["recipes"] as? [[String: Any]])
        XCTAssertNotNil(recipes[0]["tips"])
        XCTAssertNil(recipes[1]["tips"])
        XCTAssertTrue(try decodeOrFail(backupFixture("backup-v1")).recipes.allSatisfy { $0.tips.isEmpty })
    }
}
