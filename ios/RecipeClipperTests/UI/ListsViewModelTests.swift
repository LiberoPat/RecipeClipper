import XCTest
@testable import RecipeClipper

@MainActor
final class ListsViewModelTests: XCTestCase {

    private func list(_ id: Int64, _ name: String) -> RecipeList {
        RecipeList(id: id, name: name, isBuiltIn: true, isFavorites: id == 1, recipeCount: 0)
    }

    private func repositoryWithBuiltIns() -> FakeListRepository {
        let repository = FakeListRepository()
        repository.lists.send([list(1, "Favorites"), list(2, "Lunch")])
        return repository
    }

    func testStartsUnloadedSoTheScreenDoesNotFlashAnEmptyList() {
        let vm = ListsViewModel(repository: repositoryWithBuiltIns())
        XCTAssertFalse(vm.uiState.loaded)
    }

    func testListsArriveWithTheirCounts() async {
        let repository = repositoryWithBuiltIns()
        repository.stageMembership([(7, 2), (8, 2)])
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        XCTAssertTrue(vm.uiState.loaded)
        XCTAssertEqual(vm.uiState.lists.map(\.name), ["Favorites", "Lunch"])
        XCTAssertEqual(vm.uiState.lists.first { $0.id == 1 }?.recipeCount, 0)
        XCTAssertEqual(vm.uiState.lists.first { $0.id == 2 }?.recipeCount, 2)
    }

    func testEachListsCoverPhotosArriveWithItFourAtMost() async {
        let repository = repositoryWithBuiltIns()
        repository.coverPhotos.send([2: (1...5).map {
            CoverPhoto(url: "https://img/\($0).jpg", source: NoPhotoSource(uid: "uid-\($0)", title: "Recipe \($0)"))
        }])
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        XCTAssertEqual(vm.uiState.coverPhotos[2]?.map(\.url), (1...4).map { "https://img/\($0).jpg" })
        XCTAssertNil(vm.uiState.coverPhotos[1], "no photo: a plain tile")
    }

    func testEachListsPlaceholderCoversArriveFourAtMost() async {
        let repository = repositoryWithBuiltIns()
        repository.coverPlaceholders.send([1: (1...5).map { NoPhotoSource(uid: "uid-\($0)", title: "Recipe \($0)") }])
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        XCTAssertEqual(vm.uiState.coverPlaceholders[1]?.map(\.uid), (1...4).map { "uid-\($0)" })
        XCTAssertNil(vm.uiState.coverPlaceholders[2], "an empty list: the bookmark tile")
    }

    func testNothingHereIsTickedSinceNoRecipeIsInHand() async {
        let repository = repositoryWithBuiltIns()
        repository.stageMembership([(7, 2)])
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        XCTAssertFalse(vm.uiState.lists.contains { $0.containsRecipe })
    }

    func testCreatingAListFromHereLeavesItEmpty() async {
        let repository = repositoryWithBuiltIns()
        let vm = ListsViewModel(repository: repository)
        vm.onStartCreating()
        vm.onNewListNameChange("Weeknights")
        vm.onCreateList()
        await settleMain()

        XCTAssertEqual(repository.createCalls.map(\.name), ["Weeknights"])
        XCTAssertEqual(repository.createCalls.map(\.recipeId), [nil])
        XCTAssertEqual(vm.uiState.lists.first { $0.name == "Weeknights" }?.recipeCount, 0)
    }

    func testCreatingClosesTheFieldAndClearsWhatWasTyped() async {
        let vm = ListsViewModel(repository: repositoryWithBuiltIns())
        vm.onStartCreating()
        vm.onNewListNameChange("Weeknights")
        XCTAssertTrue(vm.uiState.creatingList)

        vm.onCreateList()
        await settleMain()

        XCTAssertFalse(vm.uiState.creatingList)
        XCTAssertEqual(vm.uiState.newListName, "")
    }

    func testABlankNameCreatesNothingAndKeepsTheFieldOpen() async {
        let repository = repositoryWithBuiltIns()
        let vm = ListsViewModel(repository: repository)
        vm.onStartCreating()
        vm.onNewListNameChange(" ")
        vm.onCreateList()
        await settleMain()

        XCTAssertTrue(repository.createCalls.isEmpty)
        XCTAssertTrue(vm.uiState.creatingList)
    }

    func testCancellingClearsTheField() async {
        let repository = repositoryWithBuiltIns()
        let vm = ListsViewModel(repository: repository)
        vm.onStartCreating()
        vm.onNewListNameChange("Weeknights")
        vm.onCancelCreating()
        await settleMain()

        XCTAssertFalse(vm.uiState.creatingList)
        XCTAssertEqual(vm.uiState.newListName, "")
        XCTAssertTrue(repository.createCalls.isEmpty)
    }

    func testCountLabelsSayEmptyRatherThanZero() {
        XCTAssertEqual(Strings.listCount(0), "Empty")
        XCTAssertEqual(Strings.listCount(1), "1 recipe")
        XCTAssertEqual(Strings.listCount(3), "3 recipes")
    }

    // MARK: - Reordering (#381)

    private func repositoryWithThree() -> FakeListRepository {
        let repository = FakeListRepository()
        repository.lists.send([list(1, "Favorites"), list(2, "Lunch"), list(3, "Dinner")])
        return repository
    }

    func testDraggingShowsTheNewOrderAtOnceButStoresNothingUntilTheDrop() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onDragStart(3)
        vm.onDragOver(1)
        XCTAssertEqual(vm.uiState.draggingId, 3)
        XCTAssertEqual(vm.uiState.lists.map(\.id), [3, 1, 2])
        XCTAssertTrue(repository.reorderCalls.isEmpty)

        vm.onDragEnd()
        await settleMain()

        XCTAssertEqual(repository.reorderCalls, [[3, 1, 2]])
        XCTAssertNil(vm.uiState.draggingId)
        XCTAssertEqual(vm.uiState.lists.map(\.id), [3, 1, 2])
    }

    /// The drop's write is done before the database's publication of it reaches the screen. In
    /// that gap the covers must stay where the drag put them, not snap back to the old order and
    /// then forward again (a flicker the cook sees, and a UI test can catch mid-way).
    func testTheCoversNeverSnapBackBetweenTheDropAndTheStoredOrderArriving() async {
        let repository = repositoryWithThree()
        repository.reorderPublishDelay = .milliseconds(150)
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onDragStart(3)
        vm.onDragOver(1)
        vm.onDragEnd()
        for _ in 0..<100 {
            XCTAssertEqual(vm.uiState.lists.map(\.id), [3, 1, 2])
            try? await Task.sleep(nanoseconds: 3_000_000)
        }
        await settleMain()

        XCTAssertEqual(vm.uiState.lists.map(\.id), [3, 1, 2])
        XCTAssertNil(vm.uiState.draggingId)
    }

    func testACoverDraggedOverSeveralInTurnTakesEachPlace() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onDragStart(1)
        vm.onDragOver(2)
        vm.onDragOver(3)
        vm.onDragEnd()
        await settleMain()

        XCTAssertEqual(repository.reorderCalls, [[2, 3, 1]])
    }

    func testCancellingADragPutsTheCoversBackAndStoresNothing() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onDragStart(2)
        vm.onDragOver(3)
        vm.onDragCancel()

        XCTAssertEqual(vm.uiState.lists.map(\.id), [1, 2, 3])
        XCTAssertNil(vm.uiState.draggingId)
        XCTAssertTrue(repository.reorderCalls.isEmpty)
    }

    /// A slow device can finish the lift with the finger already over the target, and the system
    /// never reports entering it. Letting go over a cover still gives the held one its place.
    func testLettingGoOverACoverTakesItsPlaceEvenIfTheDragNeverEnteredIt() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onDragStart(3)
        vm.onDrop(on: 1)
        await settleMain()

        XCTAssertEqual(repository.reorderCalls, [[3, 1, 2]])
        XCTAssertNil(vm.uiState.draggingId)
    }

    func testLettingGoOnTheScreenAroundTheCoversOnlyEndsTheDrag() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onDragStart(3)
        vm.onDrop(on: nil)
        await settleMain()

        XCTAssertTrue(repository.reorderCalls.isEmpty)
        XCTAssertNil(vm.uiState.draggingId)
    }

    func testDroppingWhereItStartedStoresNothing() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onDragStart(2)
        vm.onDragOver(3)
        vm.onDragOver(3) // back over the cover it swapped with: where it started
        vm.onDragEnd()
        await settleMain()

        XCTAssertTrue(repository.reorderCalls.isEmpty)
        XCTAssertNil(vm.uiState.draggingId)
    }

    func testADragOfNothingOrOverNothingChangesNothing() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onDragOver(2)
        vm.onDragStart(99)
        vm.onDragEnd()
        vm.onDragStart(1)
        vm.onDragOver(99)
        await settleMain()

        XCTAssertEqual(vm.uiState.lists.map(\.id), [1, 2, 3])
        XCTAssertTrue(repository.reorderCalls.isEmpty)
    }

    func testMoveEarlierAndMoveLaterStoreTheMoveAtOnce() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onMoveLater(1)
        await settleMain()
        XCTAssertEqual(vm.uiState.lists.map(\.id), [2, 1, 3])

        vm.onMoveEarlier(3)
        await settleMain()
        XCTAssertEqual(vm.uiState.lists.map(\.id), [2, 3, 1])
        XCTAssertEqual(repository.reorderCalls, [[2, 1, 3], [2, 3, 1]])
    }

    func testTheFirstCannotMoveEarlierNorTheLastLater() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onMoveEarlier(1)
        vm.onMoveLater(3)
        vm.onMoveLater(99)
        await settleMain()

        XCTAssertTrue(repository.reorderCalls.isEmpty)
    }

    func testAListCreatedDuringADragGoesLastAndTheDragsOrderStillHolds() async {
        let repository = repositoryWithThree()
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        vm.onDragStart(3)
        vm.onDragOver(1)
        await repository.createList(name: "New", addRecipeId: nil)
        await settleMain()

        XCTAssertEqual(vm.uiState.lists.map(\.id), [3, 1, 2, 100])
    }
}

