import XCTest
@testable import RecipeClipper

/// "Send as file" and the sheet a received file opens (#149, phase 2; Android's
/// ShareFileViewModelsTest), over fakes.
@MainActor
final class ShareFileViewModelsTests: XCTestCase {

    private let files = FakeBackupFiles()
    private let share = FakeShareFileRepository()
    private let url = URL(fileURLWithPath: "/tmp/Inbox/Sheet-pan chicken.recipeclipper")

    private func receiver(groceriesOn: Bool = true) -> ReceiveFileViewModel {
        ReceiveFileViewModel(files: files, share: share, groceriesOn: { groceriesOn })
    }

    private func opened(groceriesOn: Bool = true) async throws -> ReceiveFileViewModel {
        files.files[url] = try shareFixture()
        let vm = receiver(groceriesOn: groceriesOn)
        vm.open(url)
        await vm.currentWork?.value
        return vm
    }

    // MARK: Sending

    func testARecipeIsMadeIntoAFileNamedForIt() async {
        share.recipeFiles[7] = "{json}"
        let vm = SendFileViewModel(share: share)

        vm.sendRecipe(7, title: "Sheet-pan chicken")
        await vm.currentWork?.value

        XCTAssertEqual(vm.uiState.file, SentFile(text: "{json}", name: "Sheet-pan chicken.recipeclipper"))
        vm.onSent()
        XCTAssertNil(vm.uiState.file)
    }

    func testAFileThatCantBeMadeOrWrittenSaysSo() async {
        let vm = SendFileViewModel(share: share)
        vm.sendGroceries(title: "Groceries")
        await vm.currentWork?.value
        XCTAssertTrue(vm.uiState.failed)
        vm.onFailureShown()

        share.groceriesFileText = "{json}"
        vm.sendGroceries(title: "Groceries")
        await vm.currentWork?.value
        XCTAssertEqual(vm.uiState.file?.name, "Groceries.recipeclipper")
        vm.onWriteFailed()
        XCTAssertTrue(vm.uiState.failed)
        XCTAssertNil(vm.uiState.file)
    }

    func testThePantryIsMadeAsAFileNamedForTheTab() async {
        let vm = SendFileViewModel(share: share)
        vm.sendPantry(title: "Pantry")
        await vm.currentWork?.value
        XCTAssertTrue(vm.uiState.failed)
        vm.onFailureShown()

        share.pantryFileText = "{pantry}"
        vm.sendPantry(title: "Pantry")
        await vm.currentWork?.value
        XCTAssertEqual(vm.uiState.file, SentFile(text: "{pantry}", name: "Pantry.recipeclipper"))
    }

    // MARK: Receiving

    func testAFileOpenedWithTheAppShowsWhatsInsideEveryRowTicked() async throws {
        let vm = try await opened()

        let state = vm.uiState
        XCTAssertTrue(state.open)
        XCTAssertEqual(state.recipes.map(\.text), ["Sheet-pan chicken", "Grandma's lemon cake"])
        XCTAssertEqual(state.groceries.map(\.text), ["2 lb chicken thighs", "1 lemon", "baking paper"])
        // Each grocery item names its recipe.
        XCTAssertEqual(state.groceries.map(\.detail), ["Sheet-pan chicken", "Sheet-pan chicken", nil])
        XCTAssertEqual(state.pantry.map(\.text), ["Basmati rice"])
        XCTAssertEqual(state.tickedCount, 6)
    }

    func testWithoutTheTabsOnlyTheRecipesAreOffered() async throws {
        let vm = try await opened(groceriesOn: false)
        XCTAssertEqual(vm.uiState.recipes.count, 2)
        XCTAssertTrue(vm.uiState.groceries.isEmpty && vm.uiState.pantry.isEmpty)
    }

    func testAFileThatCantBeReadOrIsntOneSaysWhy() async {
        let vm = receiver()
        vm.open(url)
        await vm.currentWork?.value
        XCTAssertEqual(vm.uiState.error, .readFailed)

        files.files[url] = "{\"hello\": 1}"
        vm.open(url)
        await vm.currentWork?.value
        XCTAssertEqual(vm.uiState.error, .notABackup)
        vm.onAdd()
        await vm.currentWork?.value
        XCTAssertTrue(share.received.isEmpty)
    }

    func testAddMergesWhatIsTickedPantryItemsWhereTheReceiverChose() async throws {
        let vm = try await opened()

        vm.onToggle("r:r-cake")
        vm.onToggle("g:g-paper")
        vm.onPantryTo(.groceries)
        vm.onAdd()
        await vm.currentWork?.value

        XCTAssertEqual(share.received, [
            ShareChoice(recipeIds: ["r-chicken"], groceryIds: ["g-thighs", "g-lemon"], pantryIds: ["p-rice"], pantryTo: .groceries),
        ])
        XCTAssertEqual(vm.uiState.added, .groceries)
        // A second tap adds nothing more.
        vm.onAdd()
        await vm.currentWork?.value
        XCTAssertEqual(share.received.count, 1)
    }

    func testRecipesAloneShowTheRecipesAndPantryItemsAloneThePantry() async throws {
        var vm = try await opened()
        ["g:g-thighs", "g:g-lemon", "g:g-paper", "p:p-rice"].forEach(vm.onToggle)
        vm.onAdd()
        await vm.currentWork?.value
        XCTAssertEqual(vm.uiState.added, .recipes)

        vm = try await opened()
        ["r:r-chicken", "r:r-cake", "g:g-thighs", "g:g-lemon", "g:g-paper"].forEach(vm.onToggle)
        vm.onAdd()
        await vm.currentWork?.value
        XCTAssertEqual(vm.uiState.added, .pantry)
    }

    func testAFailedSaveKeepsTheSheetOpenAndAFullFreeLibrarySaysWhatWasLeftOut() async throws {
        let vm = try await opened()

        share.receiveResult = .failure(.saveFailed)
        vm.onAdd()
        await vm.currentWork?.value
        XCTAssertEqual(vm.uiState.error, .saveFailed)
        XCTAssertNil(vm.uiState.added)
        XCTAssertFalse(vm.uiState.adding)

        share.receiveResult = .success(ImportSummary(
            recipesAdded: 1, listsAdded: 0, recipesAlreadyHere: 0, recipesSkipped: 1, freeLimit: 20
        ))
        vm.onAdd()
        await vm.currentWork?.value
        XCTAssertEqual(vm.uiState.skippedFree, .init(skipped: 1, limit: 20))
        XCTAssertTrue(vm.uiState.open)

        vm.onDismiss()
        XCTAssertFalse(vm.uiState.open)
    }
}
