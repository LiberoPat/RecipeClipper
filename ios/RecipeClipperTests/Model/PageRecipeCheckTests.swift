import XCTest
@testable import RecipeClipper

/// The model may only pick text on the page (#103). Android's `PageRecipeCheckTest`, case for case;
/// the corpus's `Pick` rows pin the two together.
final class PageRecipeCheckTests: XCTestCase {

    private let page = """
        Grandma’s Banana Bread
        Servings: 10 slices
        Ingredients
        ▢ 3 very ripe bananas, mashed
        ⅓ cup melted butter
        1 ½ cups all-purpose flour
        12 cups popcorn
        Instructions
        1. Preheat the oven to 350°F (175°C). Bake for 55 to 65 minutes, until a tester comes out clean.
        Bake for 20-25 minutes.
        """

    private func find(_ picked: String, _ kind: PageRecipeCheck.Kind) -> String? {
        PageRecipeCheck.find(page, picked, kind: kind)
    }

    func testWhatShowsIsThePagesOwnTextFoundAfterFolding() {
        XCTAssertEqual(find("1/3 cup melted butter", .ingredient), "⅓ cup melted butter")
        XCTAssertEqual(find("grandma's banana bread", .name), "Grandma’s Banana Bread")
        XCTAssertEqual(find("1 1/2 cups all-purpose flour", .ingredient), "1 ½ cups all-purpose flour")
        XCTAssertEqual(find("3 very ripe bananas, mashed", .ingredient), "3 very ripe bananas, mashed")
    }

    func testTextThatIsNotOnThePageIsDropped() {
        XCTAssertNil(find("2 cups melted butter", .ingredient))
        XCTAssertNil(find("1/2 cup melted butter", .ingredient))
        XCTAssertNil(find("Mash the bananas.", .step))
    }

    func testASpanNeverCutsIntoANumberOrAWord() {
        XCTAssertNil(find("2 cups popcorn", .ingredient))
        XCTAssertNil(find("½ cups all-purpose flour", .ingredient))
        XCTAssertNil(find("25 minutes.", .step))
        XCTAssertNil(find("Bake for 20", .step))
        XCTAssertNil(find("ake for 55 to 65 minutes", .step))
        XCTAssertEqual(find("Bake for 55 to 65 minutes, until a tester comes out clean.", .step),
                       "Bake for 55 to 65 minutes, until a tester comes out clean.")
    }

    func testAnIngredientStartsItsLineAfterABulletOnly() {
        XCTAssertNil(find("10 slices", .ingredient))
        XCTAssertEqual(find("10 slices", .other), "10 slices")
    }

    func testDashesFoldAndANameOrStepNeedsALetter() {
        XCTAssertEqual(find("Bake for 20–25 minutes.", .step), "Bake for 20-25 minutes.")
        XCTAssertNil(find("350", .step))
        XCTAssertNil(find("", .name))
    }

    func testARecipeNeedsANamePlusIngredientsOrSteps() throws {
        let picked = PageSelection(
            name: "Grandma's Banana Bread", ingredients: ["⅓ cup melted butter", "2 eggs"],
            steps: ["Mash everything."], yield: "10 slices", prepTime: "15 minutes"
        )
        let kept = try XCTUnwrap(PageRecipeCheck.verify(page, picked))
        XCTAssertEqual(kept.name, "Grandma’s Banana Bread")
        XCTAssertEqual(kept.ingredients, ["⅓ cup melted butter"])
        XCTAssertEqual(kept.steps, [])
        XCTAssertEqual(kept.yield, "10 slices")
        XCTAssertNil(kept.prepTime)
        var renamed = picked; renamed.name = "Banana Loaf"
        XCTAssertNil(PageRecipeCheck.verify(page, renamed))
        var invented = picked; invented.ingredients = ["2 eggs"]
        XCTAssertNil(PageRecipeCheck.verify(page, invented))
    }

    // MARK: One recipe's lines only (#128)

    /// Delish's card as the #105 evaluation's window read it (trimmed), then another recipe's card.
    private let delish = """
        Creamy Tuscan Chicken
        Download the Delish app for free!
        Ingredients
        1 Tbsp. extra-virgin olive oil
        4 (6- to 8-oz.) boneless, skinless chicken breasts
        Kosher salt
        3 Tbsp. unsalted butter
        1 1/2 cups cherry tomatoes, halved
        3 cups baby spinach
        1/2 cup heavy cream
        Directions
        Step 1In a large skillet over medium heat, heat oil.
        Step 2Stir in cream and Parmesan and bring to a simmer.
        LIKE THIS RECIPE? THEN YOU'LL LOVE:
        Creamy Tuscan Orzo
        35 mins
        Ingredients
        1 cup orzo
        2 cups low-sodium chicken broth
        1/2 cup heavy cream
        Directions
        Step 1Bring the broth to a boil and stir in the orzo.
        """

    private let tuscan = [
        "1 Tbsp. extra-virgin olive oil", "4 (6- to 8-oz.) boneless, skinless chicken breasts", "Kosher salt",
        "3 Tbsp. unsalted butter", "1 1/2 cups cherry tomatoes, halved", "3 cups baby spinach", "1/2 cup heavy cream",
    ]
    private let tuscanSteps = [
        "Step 1In a large skillet over medium heat, heat oil.", "Step 2Stir in cream and Parmesan and bring to a simmer.",
    ]

    func testEveryLineOfTheRecipesOwnCardIsKept() throws {
        let kept = try XCTUnwrap(PageRecipeCheck.verify(delish, PageSelection(name: "Creamy Tuscan Chicken", ingredients: tuscan, steps: tuscanSteps)))
        XCTAssertEqual(kept.ingredients, tuscan)
        XCTAssertEqual(kept.steps, tuscanSteps)
    }

    func testLinesFoundOnlyInAnotherRecipesCardAreDropped() throws {
        let picked = PageSelection(
            name: "Creamy Tuscan Chicken",
            ingredients: Array(tuscan.prefix(3)) + ["1 cup orzo"] + Array(tuscan.dropFirst(3)) + ["2 cups low-sodium chicken broth"],
            steps: tuscanSteps + ["Step 1Bring the broth to a boil and stir in the orzo."]
        )
        let kept = try XCTUnwrap(PageRecipeCheck.verify(delish, picked))
        XCTAssertEqual(kept.ingredients, tuscan)
        XCTAssertEqual(kept.steps, tuscanSteps)
    }

    func testTheRecipeIsTheCardHoldingMostOfThePickedLines() throws {
        let orzo = PageSelection(
            name: "Creamy Tuscan Orzo", ingredients: ["1 cup orzo", "2 cups low-sodium chicken broth", "1/2 cup heavy cream", "Kosher salt"],
            steps: ["Step 1Bring the broth to a boil and stir in the orzo."]
        )
        let kept = try XCTUnwrap(PageRecipeCheck.verify(delish, orzo))
        XCTAssertEqual(kept.ingredients, ["1 cup orzo", "2 cups low-sodium chicken broth", "1/2 cup heavy cream"])
        XCTAssertEqual(kept.steps, orzo.steps)
    }

    func testASecondIngredientsHeadingBeforeTheStepsIsTheSameRecipe() {
        let cake = """
            Lemon Cake
            Ingredients
            2 cups flour
            1 cup sugar
            Ingredients for the glaze
            1 cup powdered sugar
            Instructions
            Mix the flour and sugar.
            Whisk the powdered sugar with lemon juice.
            """
        let picked = PageSelection(
            name: "Lemon Cake", ingredients: ["2 cups flour", "1 cup sugar", "1 cup powdered sugar"],
            steps: ["Mix the flour and sugar.", "Whisk the powdered sugar with lemon juice."]
        )
        XCTAssertEqual(PageRecipeCheck.verify(cake, picked), picked)
    }
}
