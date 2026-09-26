import XCTest
@testable import RecipeClipper

/// Android's GroceriesViewModelTest and AddToGroceriesViewModelTest: the Groceries tab and the
/// "Add to groceries" sheet (#50).
@MainActor
final class GroceriesViewModelTests: XCTestCase {
    private let repository = FakeGroceryRepository()

    private func add(_ lines: String...) async {
        await repository.add(lines.map { NewGroceryLine(text: $0, language: "en") })
    }

    private func viewModel(language: String? = "en") async -> GroceriesViewModel {
        let vm = GroceriesViewModel(repository: repository, pantry: FakePantryRepository(), calendar: FakePlanCalendar(), phoneLanguage: { language })
        await settleMain()
        return vm
    }

    private func rows(_ vm: GroceriesViewModel) -> [GroceryCombiner.Row] {
        (vm.uiState.sections ?? []).flatMap(\.rows)
    }

    func testTheListIsGroupedByAisleAndTheSameIngredientIsAddedUp() async {
        await add("200 g flour", "2 onions", "100 g flour")
        let vm = await viewModel()

        let sections = vm.uiState.sections!
        XCTAssertEqual(sections.map(\.aisle), [.produce, .baking])
        guard case .combined(_, let text, _) = sections[1].rows[0] else { return XCTFail("not combined") }
        XCTAssertEqual(text, "300 g flour")
    }

    func testAnItemInOtherIsFiledWhereTheModelDecidedOnceAndAUsersMoveStands() async {
        await add("2 tbsp furikake", "1 jar gochugaru flakes", "2 onions")
        let decisions = FakeDecisionRepository([.aisle("furikake", language: "en"): "spices"])
        let vm = GroceriesViewModel(
            repository: repository, pantry: FakePantryRepository(), calendar: FakePlanCalendar(), decisions: decisions
        )
        await settleMain()
        await settleMain()
        let aisles = Dictionary(uniqueKeysWithValues: repository.items.value.map { ($0.text, $0.aisle) })
        XCTAssertEqual(aisles["2 tbsp furikake"], .spices)
        XCTAssertEqual(aisles["1 jar gochugaru flakes"], .other)
        XCTAssertFalse(decisions.asked.contains { $0.input == "onions" })

        let id = repository.items.value.first { $0.text == "2 tbsp furikake" }!.id
        await repository.setAisle([id], aisle: .other)
        await settleMain()
        XCTAssertEqual(repository.items.value.first { $0.id == id }?.aisle, .other)
        XCTAssertEqual(vm.uiState.sections?.filter { $0.aisle == .other }.count, 1)
    }

    func testTheModelsAnswersRegroupTheListAndAddUpOnlyExactAmounts() async {
        await add("2 ears of corn", "2 corn", "2 eggs, beaten", "3 eggs")
        let corn = DecisionQuestion.sameGrocery("ears of corn", "corn", language: "en")
        let beaten = DecisionQuestion.trailingText(", beaten", language: "en")
        let decisions = FakeDecisionRepository([corn: "same", beaten: "note"])
        let vm = GroceriesViewModel(
            repository: repository, pantry: FakePantryRepository(), calendar: FakePlanCalendar(), decisions: decisions
        )
        // Wait for both answers to land and regroup the list: four lines in two rows.
        await settleMain { vm.uiState.sections.map { $0.flatMap(\.rows).count } == 2 }

        XCTAssertTrue(decisions.asked.contains(corn) && decisions.asked.contains(beaten))
        let all = rows(vm)
        XCTAssertEqual(all.first { $0.items.contains { $0.text == "2 corn" } }?.items.count, 2)
        let totals = all.compactMap { row -> String? in if case .combined(_, let text, _) = row { return text } else { return nil } }
        XCTAssertEqual(totals, ["5 eggs"])
    }

    func testJunkWithNoSeparatorIsHiddenOnceTheModelNamesTheIngredient() async {
        await add("2 onions dfsafs", "3 onions")
        let name = DecisionQuestion.ingredientName("2 onions dfsafs", language: "en")
        let junk = DecisionQuestion.trailingText("dfsafs", language: "en")
        let decisions = FakeDecisionRepository([name: "onions", junk: "junk"])
        let vm = GroceriesViewModel(
            repository: repository, pantry: FakePantryRepository(), calendar: FakePlanCalendar(), decisions: decisions
        )
        await settleMain { vm.uiState.sections.map { $0.flatMap(\.rows).count } == 1 }

        XCTAssertEqual(decisions.asked.filter { $0 == name || $0 == junk }, [name, junk])
        guard case .combined(_, let text, _) = rows(vm).first else { return XCTFail("not combined") }
        XCTAssertEqual(text, "5 onions")
        XCTAssertEqual(GroceryCombiner.lines(rows(vm)[0]), ["2 onions", "3 onions"])
        XCTAssertEqual(vm.shareText(title: "Groceries", aisleName: \.key), "Groceries\n\nproduce\n- 5 onions")
        XCTAssertTrue(repository.items.value.contains { $0.text == "2 onions dfsafs" })
    }

    func testALoneLinesTrailingTextIsAskedAboutHiddenWhenJunkAndShownWhenANote() async {
        await add("2 eggs (dfsafs -", "1 cup milk, warmed")
        let junk = DecisionQuestion.trailingText("(dfsafs -", language: "en")
        let note = DecisionQuestion.trailingText(", warmed", language: "en")
        let decisions = FakeDecisionRepository([junk: "junk", note: "note"])
        let vm = GroceriesViewModel(
            repository: repository, pantry: FakePantryRepository(), calendar: FakePlanCalendar(), decisions: decisions
        )
        let shown = { self.rows(vm).compactMap { row -> String? in if case .single(let item) = row { return item.text } else { return nil } } }
        await settleMain { Set(shown()) == ["2 eggs", "1 cup milk, warmed"] }

        XCTAssertEqual(decisions.asked.filter { $0 == junk }.count, 1)
        XCTAssertTrue(decisions.asked.contains(note))
        XCTAssertEqual(Set(shown()), ["2 eggs", "1 cup milk, warmed"])
        XCTAssertTrue(repository.items.value.contains { $0.text == "2 eggs (dfsafs -" })
    }

    func testALoneLineWithNoAnswerShowsExactlyAsToday() async {
        await add("2 eggs (dfsafs -", "1 cup milk, warmed")
        let junk = DecisionQuestion.trailingText("(dfsafs -", language: "en")
        let decisions = FakeDecisionRepository([:])
        let vm = GroceriesViewModel(
            repository: repository, pantry: FakePantryRepository(), calendar: FakePlanCalendar(), decisions: decisions
        )
        await settleMain { decisions.asked.contains(junk) }
        XCTAssertTrue(decisions.asked.contains(junk))
        XCTAssertEqual(vm.uiState.sections, GroceryCombiner.sections(repository.items.value))
    }

    func testWithNoNameAnsweredTheJunkLineShowsExactlyAsToday() async {
        await add("2 onions dfsafs", "3 onions")
        let vm = GroceriesViewModel(
            repository: repository, pantry: FakePantryRepository(), calendar: FakePlanCalendar(), decisions: FakeDecisionRepository([:])
        )
        for _ in 0..<4 { await settleMain() }
        XCTAssertEqual(vm.uiState.sections, GroceryCombiner.sections(repository.items.value))
        XCTAssertTrue(rows(vm).contains { $0.items.contains { $0.text == "2 onions dfsafs" } })
    }

    func testWithoutAnswersTheGroceryListIsExactlyAsToday() async {
        await add("2 ears of corn", "2 corn", "2 eggs, beaten", "3 eggs")
        let vm = GroceriesViewModel(
            repository: repository, pantry: FakePantryRepository(), calendar: FakePlanCalendar(), decisions: FakeDecisionRepository([:])
        )
        await settleMain()
        await settleMain()
        XCTAssertEqual(vm.uiState.sections, GroceryCombiner.sections(repository.items.value))
    }

    func testATypedItemGoesOnTheListInThePhonesLanguage() async {
        let vm = await viewModel(language: "de")
        vm.onDraftChange("  Milch ")
        vm.onAddTyped()
        await settleMain()

        let item = repository.items.value.first
        XCTAssertEqual(item?.text, "Milch")
        XCTAssertEqual(item?.language, "de")
        XCTAssertEqual(item?.aisle, .dairy)
        XCTAssertEqual(vm.uiState.draft, "")
    }

    func testAPhoneLanguageWithNoWordsReadsTypedItemsInEnglish() async {
        let vm = await viewModel(language: "ko")
        vm.onDraftChange("milk")
        vm.onAddTyped()
        await settleMain()
        XCTAssertEqual(repository.items.value.first?.language, "en")
    }

    func testTickingACombinedRowTicksEveryLine() async {
        await add("200 g flour", "100 g flour")
        let vm = await viewModel()

        vm.onToggle(rows(vm)[0])
        await settleMain()
        XCTAssertTrue(repository.items.value.allSatisfy(\.checked))
        XCTAssertTrue(vm.uiState.hasChecked)

        vm.onToggle(rows(vm)[0])
        await settleMain()
        XCTAssertFalse(repository.items.value.contains(where: \.checked))
    }

    func testMovingARowToAnotherAisleKeepsItThere() async {
        await add("1 jar pickles")
        let vm = await viewModel()
        XCTAssertEqual(vm.uiState.sections?.first?.aisle, .other)

        vm.onMoveStart(rows(vm)[0])
        vm.onMoveTo(.condiments)
        await settleMain()

        XCTAssertEqual(vm.uiState.sections?.first?.aisle, .condiments)
        XCTAssertNil(vm.uiState.moving)
    }

    func testADeleteCanBeUndone() async {
        await add("2 onions", "1 cup milk")
        let vm = await viewModel()

        vm.onDelete(rows(vm)[0], label: "2 onions")
        await settleMain()
        XCTAssertEqual(repository.items.value.map(\.text), ["1 cup milk"])
        XCTAssertEqual(vm.uiState.removed?.label, "2 onions")

        vm.onUndoRemove()
        await settleMain()
        XCTAssertEqual(repository.items.value.map(\.text), ["2 onions", "1 cup milk"])
        XCTAssertNil(vm.uiState.removed)
    }

    func testClearCheckedRemovesOnlyTheTickedItemsAndCanBeUndone() async {
        await add("2 onions", "1 cup milk")
        let vm = await viewModel()
        await repository.setChecked([repository.items.value[0].id], checked: true)
        await settleMain()

        vm.onClearChecked()
        await settleMain()
        XCTAssertEqual(repository.items.value.map(\.text), ["1 cup milk"])
        XCTAssertNotNil(vm.uiState.removed)
        XCTAssertNil(vm.uiState.removed?.label)

        vm.onUndoRemove()
        await settleMain()
        XCTAssertEqual(repository.items.value.count, 2)
    }

    func testTheSharedTextIsWhatIsLeftToBuy() async {
        let vm = await viewModel()
        XCTAssertNil(vm.shareText(title: "Groceries", aisleName: \.key))

        await add("2 onions", "1 cup milk")
        await repository.setChecked([repository.items.value[1].id], checked: true)
        await settleMain()
        XCTAssertEqual(vm.shareText(title: "Groceries", aisleName: \.key), "Groceries\n\nproduce\n- 2 onions")
    }

    // MARK: - The add sheet

    func testARecipesLinesAreAllTickedAndHeadingsAndBlanksAreLeftOut() {
        let vm = AddToGroceriesViewModel(repository: repository, preferences: FakeAppPreferences(), pantry: FakePantryRepository())
        vm.setRecipe(7, title: "Pancakes", language: "en", rendered: ["For the batter:", "2 cups flour", "", "2 eggs"])
        XCTAssertEqual(vm.uiState.sources?.first?.lines, ["2 cups flour", "2 eggs"])
        XCTAssertEqual(vm.uiState.tickedCount, 2)
    }

    func testOnlyTheTickedLinesAreAddedWithTheirRecipe() async {
        let vm = AddToGroceriesViewModel(repository: repository, preferences: FakeAppPreferences(), pantry: FakePantryRepository())
        vm.setRecipe(7, title: "Pancakes", language: "en", rendered: ["2 cups flour", "2 eggs", "1 cup milk"])
        vm.onToggle(SourceLine(source: "recipe-7", index: 1))
        vm.onAdd()
        await settleMain()

        XCTAssertEqual(repository.items.value.map(\.text), ["2 cups flour", "1 cup milk"])
        XCTAssertTrue(repository.items.value.allSatisfy { $0.recipeId == 7 && $0.language == "en" })
        XCTAssertTrue(vm.uiState.added)
    }

    func testTheWeeksRecipesComeAtTheirPlannedServingsInTheUsersUnits() async {
        repository.planned = [
            PlannedIngredients(entryId: 1, day: 100, servings: 8, recipeId: 7, title: "Pancakes",
                               ingredients: ["2 cups flour", "Garnish:"], yield: "Serves 4", language: "en"),
            PlannedIngredients(entryId: 2, day: 102, servings: nil, recipeId: 9, title: "Soup",
                               ingredients: ["1 lb potatoes"], yield: "Serves 2", language: "en"),
            PlannedIngredients(entryId: 3, day: 110, servings: nil, recipeId: 5, title: "Next week",
                               ingredients: ["1 onion"], yield: nil, language: "en"),
        ]
        let vm = AddToGroceriesViewModel(repository: repository, preferences: FakeAppPreferences(unitSystem: .metric), pantry: FakePantryRepository())
        vm.loadWeek(100)
        XCTAssertNil(vm.uiState.sources)
        await vm.loading?.value

        let sources = vm.uiState.sources ?? []
        XCTAssertEqual(sources.map(\.title), ["Pancakes", "Soup"])
        XCTAssertEqual(sources.first?.lines, ["480 g flour"])
        XCTAssertEqual(sources.last?.lines, ["455 g potatoes"])

        vm.onAdd()
        await settleMain()
        XCTAssertEqual(repository.items.value.map(\.plannedDay), [100, 102])
    }
}

/// A few of Kotlin's GroceryCombinerTest and AislesTest cases, for the list's shape. The
/// combining and aisle rules themselves are pinned by the differential corpus.
final class GroceryCombinerTests: XCTestCase {
    private var nextId: Int64 = 1

    private func item(_ text: String, checked: Bool = false, language: String? = "en", aisle: Aisle? = nil) -> GroceryItem {
        defer { nextId += 1 }
        return GroceryItem(
            id: nextId, text: text, language: language,
            aisle: aisle ?? Aisles.of(text, words: LanguageWords.forTag(language)), checked: checked, sortOrder: Int(nextId)
        )
    }

    func testSectionsFollowTheAisleOrderAndLeaveEmptyOnesOut() {
        let sections = GroceryCombiner.sections([item("1 tsp salt"), item("2 onions"), item("paper towels"), item("1 cup milk")])
        XCTAssertEqual(sections.map(\.aisle), [.produce, .dairy, .spices, .other])
    }

    func testTheSameIngredientCombinesOrSitsTogether() {
        let rows = GroceryCombiner.sections([item("200 g flour"), item("100 g flour"), item("1 cup sugar"), item("100 g sugar")])[0].rows
        XCTAssertEqual(rows.count, 2)
        guard case .combined(_, let text, let items) = rows[0] else { return XCTFail("flour") }
        XCTAssertEqual(text, "300 g flour")
        XCTAssertEqual(items.count, 2)
        guard case .together(let name, let sugar) = rows[1] else { return XCTFail("sugar") }
        XCTAssertEqual(name, "sugar")
        XCTAssertEqual(sugar.map(\.text), ["1 cup sugar", "100 g sugar"])
    }

    func testCheckedLinesComeAfterAndNeverCombineWithUnchecked() {
        let rows = GroceryCombiner.sections([item("200 g flour", checked: true), item("100 g flour"), item("1 tsp baking powder")])[0].rows
        XCTAssertEqual(rows.flatMap(\.items).map(\.text), ["100 g flour", "1 tsp baking powder", "200 g flour"])
        XCTAssertTrue(rows.allSatisfy { if case .single = $0 { return true } else { return false } })
    }

    func testLinesWithNoNameOrNoLanguageGroupOnlyWithTheSameLine() {
        let rows = GroceryCombiner.sections([
            item("salt and pepper"), item("salt and pepper"), item("olive oil and butter"),
            item("2 eggs", language: nil), item("2 eggs", language: nil),
        ]).flatMap(\.rows)
        let texts: [String] = rows.map {
            switch $0 {
            case .combined(_, let text, _): return text
            case .single(let item): return item.text
            case .together(let name, _): return "together \(name)"
            }
        }
        XCTAssertEqual(texts, ["salt and pepper × 2", "olive oil and butter", "2 eggs × 2"])
    }

    // The owner's report: a recipe added three times showed "2 corn" three times, each with a
    // tick of its own.
    func testTheSameLineAddedThreeTimesIsOneRow() {
        for (line, total) in [("2 corn", "6 corn"), ("1 cup milk", "3 cup milk"), ("2 corn (dfsafs -", "6 corn (dfsafs -")] {
            let rows = GroceryCombiner.sections((0..<3).map { _ in item(line) }).flatMap(\.rows)
            XCTAssertEqual(rows.count, 1, line)
            guard case .combined(_, let text, let items) = rows[0] else { return XCTFail(line) }
            XCTAssertEqual(text, total)
            XCTAssertEqual(items.count, 3)
            XCTAssertEqual(GroceryCombiner.lines(rows[0]), ["\(line) × 3"])
        }
        XCTAssertEqual(GroceryCombiner.combine(["2 corn (about 1 lb)", "2 corn (about 1 lb)"], words: .english), "2 corn (about 1 lb) × 2")
    }

    func testLinesThatCannotBeSummedAreOneRowWithTheirLinesUnderIt() {
        let rows = GroceryCombiner.sections([item("1 cup sugar"), item("100 g sugar"), item("1 cup sugar")])[0].rows
        XCTAssertEqual(rows.count, 1)
        guard case .together = rows[0] else { return XCTFail("sugar") }
        XCTAssertEqual(GroceryCombiner.lines(rows[0]), ["1 cup sugar × 2", "100 g sugar"])
    }

    func testAnItemMovedToAnotherAisleLeavesItsGroup() {
        let sections = GroceryCombiner.sections([item("200 g flour"), item("100 g flour", aisle: .other)])
        XCTAssertEqual(sections.map(\.aisle), [.baking, .other])
    }

    func testTheSharedTextListsWhatIsLeftToBuy() {
        let sections = GroceryCombiner.sections([
            item("2 onions"), item("200 g flour"), item("100 g flour"), item("1 cup sugar"), item("100 g sugar"),
            item("1 cup milk", checked: true),
        ])
        XCTAssertEqual(
            GroceryShareText.format(sections, title: "Groceries") { $0.key.capitalized },
            "Groceries\n\nProduce\n- 2 onions\n\nBaking\n- 300 g flour\n- 1 cup sugar\n- 100 g sugar"
        )
    }

    func testAnUnknownStoredKeyIsOther() {
        XCTAssertEqual(Aisle.fromKey("haberdashery"), .other)
        XCTAssertEqual(Aisle.fromKey(nil), .other)
        XCTAssertEqual(Aisle.fromKey("dairy"), .dairy)
    }
}
