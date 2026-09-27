import XCTest
@testable import RecipeClipper

/// The same cases and expectations as Android's `RecipeTextSplitterTest`.
final class RecipeTextSplitterTests: XCTestCase {

    private func split(_ text: String) -> SplitRecipe? { RecipeTextSplitter.split(text) }

    func testSplitsAMarkdownRecipeWithBoldHeadersBulletsAndNumberedSteps() throws {
        let result = try XCTUnwrap(split("""
            **Ingredients**

            * 2 cups flour
            * 1 tsp salt

            **Instructions**

            1. Mix everything.
            2. Bake for 20 minutes.
            """))
        XCTAssertEqual(result.ingredients, ["2 cups flour", "1 tsp salt"])
        XCTAssertEqual(result.instructions, ["Mix everything.", "Bake for 20 minutes."])
        XCTAssertNil(result.yield)
    }

    func testProseWithoutHeadersIsNeverSplit() {
        XCTAssertNil(split("I mixed 2 cups of flour with some butter and baked it at 350 for 20 minutes. So good!"))
        XCTAssertNil(split("2 cups flour\n1 tsp salt\nMix and bake for 20 minutes."))
    }

    func testNeedsBothSectionsEachWithLines() {
        XCTAssertNil(split("Ingredients:\n2 cups flour\n1 tsp salt"))
        XCTAssertNil(split("Directions:\nMix.\nBake."))
        XCTAssertNil(split("Ingredients:\n\nDirections:\nMix.\nBake."))
        XCTAssertNil(split("Ingredients:\n2 cups flour\nDirections:\n"))
    }

    func testAHeaderMustBeTheWholeLine() {
        XCTAssertNil(split("Ingredients: flour, salt, water\nInstructions: mix and bake"))
        XCTAssertNil(split("The ingredients are simple\n2 cups flour\nThe method is easy\nBake."))
    }

    func testRecognisesTheUsualHeaderSpellings() {
        XCTAssertEqual(RecipeTextSplitter.section("Ingredients"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("INGREDIENTS:"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("You'll need:"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("Ingredients (serves 4)"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("Ingredients for the cake:"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("Directions"), .instructions)
        XCTAssertEqual(RecipeTextSplitter.section("Method -"), .instructions)
        XCTAssertEqual(RecipeTextSplitter.section("Steps:"), .instructions)
        XCTAssertEqual(RecipeTextSplitter.section("How to make it"), .instructions)
        XCTAssertEqual(RecipeTextSplitter.section("Notes:"), .end)
        XCTAssertEqual(RecipeTextSplitter.section("Edit: fixed formatting"), .end)
        XCTAssertEqual(RecipeTextSplitter.section("EDIT 2: thanks all"), .end)
        XCTAssertNil(RecipeTextSplitter.section("Ingredients for this are cheap"))
        XCTAssertNil(RecipeTextSplitter.section("Preparation time: 10 min"))
        XCTAssertNil(RecipeTextSplitter.section("Update the seasoning to taste: salt"))
        XCTAssertNil(RecipeTextSplitter.section("2 cups flour"))
    }

    func testCleansMarkdownOffEachLine() {
        let clean = RecipeTextSplitter.cleanLine
        XCTAssertEqual(clean("* 2 cups flour"), "2 cups flour")
        XCTAssertEqual(clean("- 2 cups flour"), "2 cups flour")
        XCTAssertEqual(clean("• 2 cups flour"), "2 cups flour")
        XCTAssertEqual(clean("1. Mix."), "Mix.")
        XCTAssertEqual(clean("2) Mix."), "Mix.")
        XCTAssertEqual(clean("Step 3: Mix."), "Mix.")
        XCTAssertEqual(clean("1.5 cups milk"), "1.5 cups milk")
        XCTAssertEqual(clean("**Ingredients:**"), "Ingredients:")
        XCTAssertEqual(clean("## Directions"), "Directions")
        XCTAssertEqual(clean("> __Directions__"), "Directions")
        XCTAssertEqual(clean("#10 can tomatoes"), "#10 can tomatoes")
        XCTAssertEqual(clean("[King Arthur](https://example.com/flour) flour"), "King Arthur flour")
        XCTAssertEqual(clean("salt &amp; pepper"), "salt & pepper")
        XCTAssertEqual(clean("1 cup sugar\\*"), "1 cup sugar*")
        XCTAssertEqual(clean("*butter, softened*"), "butter, softened")
        XCTAssertEqual(clean("---"), "")
        XCTAssertEqual(clean("&#x200B;"), "")
        XCTAssertEqual(clean("  2 eggs  "), "2 eggs")
        // Reddit's editor escapes what would otherwise be Markdown.
        XCTAssertEqual(clean("1\\. Mix."), "Mix.")
        XCTAssertEqual(clean("\\- 2 cups flour"), "2 cups flour")
        XCTAssertEqual(clean("▢ 2 cups flour"), "2 cups flour")
        XCTAssertEqual(clean("・1 pound of pork belly"), "1 pound of pork belly")
        XCTAssertEqual(clean("**Glaze** *(to be used during grilling)*"), "Glaze (to be used during grilling)")
        XCTAssertEqual(clean("Bake 20 minutes.\\"), "Bake 20 minutes.")
        XCTAssertEqual(clean("-5°C freezer"), "-5°C freezer")
        XCTAssertEqual(clean("==="), "")
    }

    func testAcceptsTheHeaderStylesPeopleTypeOnReddit() {
        let ingredientHeaders = [
            "**Ingredients**", "**Ingredients:**", "**Ingredients**:", "Ingredients:", "## Ingredients", "INGREDIENTS",
            "__Ingredients__", "*Ingredients*", "Ingredients 🛒", "What you’ll need:", "**Things You'll Need**",
            "**Ingredients** (serves 4)", "**Ingredients [for 3~4 people]**",
        ]
        let stepHeaders = [
            "**Instructions**", "**Directions:**", "**Method**:", "Steps:", "## Preparation", "DIRECTIONS",
            "__Method__", "*Instructions*", "Directions 👇", "Procedure:", "**Cooking steps (for the sauce):**",
            "**Instructions/Method**", "How to make it:",
        ]
        for (i, s) in zip(ingredientHeaders, stepHeaders) {
            let result = split("A story first.\n\n\(i)\n\n* 2 cups flour\n* 1 tsp salt\n\n\(s)\n\n1. Mix.\n2. Bake.")
            XCTAssertEqual(result?.ingredients, ["2 cups flour", "1 tsp salt"], "\(i) / \(s)")
            XCTAssertEqual(result?.instructions, ["Mix.", "Bake."], "\(i) / \(s)")
        }
    }

    func testAHeaderSetApartAsOneMayHaveAFewWordsBeforeItsKeyword() {
        func header(_ raw: String) -> RecipeTextSplitter.Header? { RecipeTextSplitter.header(RecipeTextSplitter.line(raw)) }
        XCTAssertEqual(header("**Ingredient amounts**:")?.section, .ingredients)
        XCTAssertNil(header("**Ingredient amounts**:")?.label)
        XCTAssertEqual(header("**Cooking instructions**")?.section, .instructions)
        XCTAssertNil(header("**Cooking instructions**")?.label)
        // A word that names a group keeps it as the group's heading.
        XCTAssertEqual(header("**Dry ingredients**")?.section, .ingredients)
        XCTAssertEqual(header("**Dry ingredients**")?.label, "Dry ingredients:")
        XCTAssertEqual(header("SAUCE INGREDIENTS")?.label, "SAUCE INGREDIENTS:")
        XCTAssertEqual(header("**Chef's notes**")?.section, .end)
        // Not set apart, or a step: an ordinary line.
        XCTAssertNil(header("Dry ingredients"))
        XCTAssertNil(header("**Mix the dry ingredients**"))
        XCTAssertNil(header("**Stir in the wet ingredients**"))
        XCTAssertNil(header("1. **Instructions**"))
    }

    func testAMisspeltHeaderSetApartAsOneIsStillAHeader() {
        XCTAssertEqual(RecipeTextSplitter.section("Ingredeints:"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("**Ingrediants**"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("** Intructions**"), .instructions)
        XCTAssertEqual(RecipeTextSplitter.section("DIRECTONS"), .instructions)
        XCTAssertNil(RecipeTextSplitter.section("Ingredeints"))
        XCTAssertNil(RecipeTextSplitter.section("**Introductions**"))
    }

    func testStepsWithNoHeaderAreANumberedListStartingAt1() throws {
        let result = try XCTUnwrap(split("**Ingredients**\n\n- 2 cups flour\n- 1 egg\n\n1. Mix.\n2. Bake at 350°F.\n\nEnjoy"))
        XCTAssertEqual(result.ingredients, ["2 cups flour", "1 egg"])
        XCTAssertEqual(result.instructions, ["Mix.", "Bake at 350°F.", "Enjoy"])
        // Numbered ingredients, then steps numbered again from 1.
        let numbered = try XCTUnwrap(split("Ingredients:\n1. 2 cups flour\n2. 1 egg\n1. Mix.\n2. Bake."))
        XCTAssertEqual(numbered.ingredients, ["2 cups flour", "1 egg"])
        XCTAssertEqual(numbered.instructions, ["Mix.", "Bake."])
        // "Step 1" labels, bare or with a title.
        let labelled = try XCTUnwrap(split("INGREDIENTS\n2 cups flour\n\n**Step 1**\nMix the flour.\n\n**Step 2: Bake**\nBake 20 minutes."))
        XCTAssertEqual(labelled.instructions, ["Mix the flour.", "Bake:", "Bake 20 minutes."])
    }

    func testIngredientsWithNoHeaderAreTheAmountLinesJustAboveTheSteps() throws {
        let result = try XCTUnwrap(split("""
            I scaled one down for you. It makes about two servings.
            2.5 oz. frozen spinach
            Sour cream – 1/8 cup
            Salt to taste

            Directions:
            Stir together and chill.
            """))
        XCTAssertEqual(result.ingredients, ["2.5 oz. frozen spinach", "Sour cream – 1/8 cup", "Salt to taste"])
        XCTAssertEqual(result.instructions, ["Stir together and chill."])
        // A label above the list isn't an ingredient; neither list needs a header.
        let bare = try XCTUnwrap(split("Recipe:\n\n* 1 cup dates\n* 1/4 cup honey\n* oats\n\n1. Blend the dates.\n2. Press into a pan."))
        XCTAssertEqual(bare.ingredients, ["1 cup dates", "1/4 cup honey", "oats"])
        XCTAssertEqual(bare.instructions, ["Blend the dates.", "Press into a pan."])
    }

    func testChatterNeverSplitsEvenWithANumberOrAListInIt() {
        XCTAssertNil(split("I used 2 cups of flour and it was fine.\nDirections:\nMix."))
        XCTAssertNil(split("My mum's way:\n1. lots of cheese\n2. a slow sauce\n3. patience"))
        XCTAssertNil(split("Honestly just wing it lol\n\nSteps:\n1. Buy it.\n2. Eat it."))
        XCTAssertNil(split("**Ingredients**\nwhatever is in the fridge\n\nThen bake it until it's done."))
    }

    func testABoldLineInsideASectionNamesAGroup() throws {
        let result = try XCTUnwrap(split("**Ingredients**\n**Cake**\n- 2 cups flour\n## Frosting\n- 1 cup sugar\n**Method**\n1. Bake.\n**Make the frosting**\n2. Whip."))
        XCTAssertEqual(result.ingredients, ["Cake:", "2 cups flour", "Frosting:", "1 cup sugar"])
        XCTAssertEqual(result.instructions, ["Bake.", "Make the frosting:", "Whip."])
        // A bold amount stays as written; headings alone aren't a recipe.
        XCTAssertEqual(split("Ingredients\n**2 cups flour**\nMethod\nBake.")?.ingredients, ["2 cups flour"])
        XCTAssertNil(split("Ingredients\n**Cake**\nMethod\nBake."))
    }

    func testALineWithABareLinkIsLeftOutAYieldOrTimeAmongTheIngredientsIsRead() throws {
        let result = try XCTUnwrap(split("Ingredients\nServes 2\nPrep time: 5 min\n1 egg\nMethod\nBoil.\nMore on my blog: https://example.com/eggs"))
        XCTAssertEqual(result.ingredients, ["1 egg"])
        XCTAssertEqual(result.instructions, ["Boil."])
        XCTAssertEqual(result.yield, "Serves 2")
        XCTAssertEqual(result.prepTime, "5m")
    }

    func testTheStoryIsDroppedButALabelledYieldAndTimesAreKept() throws {
        let result = try XCTUnwrap(split("""
            This one is from my aunt, who made it every summer.
            Serves 4
            Prep time: 10 min
            Cook time: 1 hour 30 minutes
            Total time: Overnight

            Ingredients
            2 eggs
            Instructions
            Whisk.
            """))
        XCTAssertEqual(result.yield, "Serves 4")
        XCTAssertEqual(result.prepTime, "10m")
        XCTAssertEqual(result.cookTime, "1h 30m")
        XCTAssertEqual(result.totalTime, "Overnight")
        XCTAssertEqual(result.ingredients, ["2 eggs"])
        XCTAssertEqual(result.instructions, ["Whisk."])
    }

    func testALabelledYieldLosesItsLabelServesAndMakesStayWhole() {
        XCTAssertEqual(split("Yield: 12 cookies\nIngredients\n1 egg\nMethod\nBake.")?.yield, "12 cookies")
        XCTAssertEqual(split("Servings: 4\nIngredients\n1 egg\nMethod\nBake.")?.yield, "4")
        XCTAssertEqual(split("Makes 2 loaves\nIngredients\n1 egg\nMethod\nBake.")?.yield, "Makes 2 loaves")
    }

    func testNotesAndEditsAfterTheStepsAreDropped() {
        let result = split("Ingredients\n1 egg\nDirections\nBoil for 7 minutes.\nNotes\nUse fresh eggs.\nEdit: typo")
        XCTAssertEqual(result?.instructions, ["Boil for 7 minutes."])
    }

    func testSectionsMayRepeatAndComeInEitherOrder() throws {
        let result = try XCTUnwrap(split("""
            For the sauce
            Method
            Simmer the tomatoes.
            Ingredients
            1 can tomatoes
            Ingredients for the pasta:
            200 g spaghetti
            Directions
            Boil the pasta.
            """))
        XCTAssertEqual(result.ingredients, ["1 can tomatoes", "200 g spaghetti"])
        XCTAssertEqual(result.instructions, ["Simmer the tomatoes.", "Boil the pasta."])
    }

    func testASubHeadingInsideASectionStaysAsWritten() {
        let result = split("Ingredients\nFor the dough:\n2 cups flour\nInstructions\nKnead.")
        XCTAssertEqual(result?.ingredients, ["For the dough:", "2 cups flour"])
    }

    func testWindowsAndOldMacLineEndingsSplitTheSame() {
        let expected = split("Ingredients\n1 egg\nMethod\nBoil.")
        XCTAssertNotNil(expected)
        XCTAssertEqual(split("Ingredients\r\n1 egg\r\nMethod\r\nBoil."), expected)
        XCTAssertEqual(split("Ingredients\r1 egg\rMethod\rBoil."), expected)
    }
}
