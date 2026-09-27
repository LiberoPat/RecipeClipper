import XCTest
@testable import RecipeClipper

/// "I made this" (#116; Android's CookedPhotosViewModelTest): adding opens the new photo, its
/// note waits for a pause, a new day keeps the note, and a delete can be undone until it stands.
/// Also the Recipes screen's Recently cooked sort.
@MainActor
final class CookedPhotosViewModelTests: XCTestCase {
    private let photos = FakeCookedPhotoRepository()

    private func viewModel(sleep: @escaping Sleep = immediateSleep) async -> CookedPhotosViewModel {
        let vm = CookedPhotosViewModel(repository: photos, sleep: sleep)
        vm.setRecipe(7)
        await settleMain()
        return vm
    }

    func testAddedPicturesBecomePhotosTheFirstOpensAndABrokenOneSaysSo() async {
        let vm = await viewModel()
        vm.onAdd([Data([1]), Data(), Data([2])])
        await vm.settleWrites()
        await settleMain()
        XCTAssertEqual(vm.uiState.photos.count, 2)
        XCTAssertEqual(vm.uiState.open?.id, 1)
        XCTAssertTrue(vm.uiState.addFailed)
        vm.onAddFailedShown()
        XCTAssertFalse(vm.uiState.addFailed)
    }

    func testOnlyThisRecipesPhotosShow() async {
        photos.photo(recipeId: 7)
        photos.photo(recipeId: 8)
        let vm = await viewModel()
        XCTAssertEqual(vm.uiState.photos.map(\.recipeId), [7])
    }

    func testTheNoteIsWrittenOncePausedTrimmedAndAtOnceOnClose() async {
        let photo = photos.photo(recipeId: 7)
        let clock = TestClock()
        let vm = await viewModel(sleep: clock.sleep)
        vm.onOpen(photo)
        vm.onNoteChange("Less")
        vm.onNoteChange("Less sugar ")
        await clock.advance(by: 499)
        XCTAssertTrue(photos.edits.isEmpty)
        await clock.advance(by: 1)
        await vm.settleWrites()
        XCTAssertEqual(photos.edits.last?.note, "Less sugar")

        vm.onNoteChange("Less sugar, more salt")
        vm.onClose()
        await vm.settleWrites()
        XCTAssertEqual(photos.edits.last?.note, "Less sugar, more salt")
        XCTAssertNil(vm.uiState.open)
    }

    func testANewDayKeepsTheNote() async {
        let photo = photos.photo(recipeId: 7, note: "Good")
        let vm = await viewModel()
        vm.onOpen(photo)
        vm.onDayChange(19_990)
        await vm.settleWrites()
        await settleMain()
        XCTAssertEqual(photos.edits.last?.day, 19_990)
        XCTAssertEqual(photos.edits.last?.note, "Good")
        XCTAssertEqual(vm.uiState.open?.day, 19_990)
    }

    func testADeletedPhotoComesBackWithUndoAndItsFileGoesOnlyOnceTheDeleteStands() async {
        let photo = photos.photo(recipeId: 7)
        let vm = await viewModel()
        vm.onOpen(photo)
        vm.onDelete()
        await vm.settleWrites()
        await settleMain()
        XCTAssertEqual(vm.uiState.deleted, photo)
        XCTAssertTrue(vm.uiState.photos.isEmpty)
        vm.onUndoDelete()
        await vm.settleWrites()
        await settleMain()
        XCTAssertEqual(vm.uiState.photos.map(\.id), [photo.id])
        XCTAssertTrue(photos.forgotten.isEmpty)

        vm.onOpen(photo)
        vm.onDelete()
        await vm.settleWrites()
        vm.onDeleteSettled()
        await vm.settleWrites()
        XCTAssertEqual(photos.forgotten, [photo.fileName!])
    }

    // The pantry's use-up sheet (#147) follows a photo just added, once its note is done.

    func testAPhotoJustAddedSaysTheRecipeWasCookedOnceItCloses() async {
        let vm = await viewModel()
        vm.onAdd([Data([1])])
        await vm.settleWrites()
        await settleMain()
        XCTAssertFalse(vm.uiState.madeThis)

        vm.onClose()
        XCTAssertTrue(vm.uiState.madeThis)
        vm.onMadeThisHandled()
        XCTAssertFalse(vm.uiState.madeThis)

        // An older photo opened and closed says nothing.
        vm.onOpen(vm.uiState.photos[0])
        vm.onClose()
        XCTAssertFalse(vm.uiState.madeThis)
    }

    func testAPictureThatCouldntBeAddedOrOneDeletedAtOnceSaysNothing() async {
        let vm = await viewModel()
        vm.onAdd([Data()])
        await vm.settleWrites()
        vm.onClose()
        XCTAssertFalse(vm.uiState.madeThis)

        vm.onAdd([Data([2])])
        await vm.settleWrites()
        await settleMain()
        vm.onDelete()
        await vm.settleWrites()
        XCTAssertFalse(vm.uiState.madeThis)
    }

    // "Mark as cooked" (#173): the same entry with no photo, the same signal.

    func testMarkingAsCookedOpensADatedEntryWithNoPhotoAndClosingItSaysTheRecipeWasCooked() async {
        let vm = await viewModel()
        vm.onMarkCooked()
        await vm.settleWrites()
        await settleMain()

        let open = vm.uiState.open
        XCTAssertNotNil(open)
        XCTAssertEqual(open?.hasPhoto, false)
        XCTAssertEqual(open?.day, photos.today)
        XCTAssertEqual(vm.uiState.photos.map(\.id), open.map { [$0.id] })
        XCTAssertFalse(vm.uiState.madeThis)

        vm.onNoteChange("Doubled the garlic")
        vm.onClose()
        await vm.settleWrites()
        XCTAssertEqual(photos.photos.value.first?.note, "Doubled the garlic")
        XCTAssertTrue(vm.uiState.madeThis)
    }

    func testAMarkDeletedAtOnceSaysNothingAndItsUndoBringsItBack() async {
        let vm = await viewModel()
        vm.onMarkCooked()
        await vm.settleWrites()
        await settleMain()
        vm.onDelete()
        await vm.settleWrites()
        await settleMain()
        XCTAssertFalse(vm.uiState.madeThis)
        XCTAssertEqual(vm.uiState.deleted?.hasPhoto, false)

        vm.onUndoDelete()
        await vm.settleWrites()
        await settleMain()
        XCTAssertEqual(vm.uiState.photos.count, 1)
        XCTAssertTrue(photos.forgotten.isEmpty)
    }

    func testRecentlyCookedPutsCookedRecipesFirstOnlyWithItsFlag() async {
        let repository = FakeRecipeRepository()
        var two = testSummary(2), one = testSummary(1)
        two.lastCookedDay = 10
        one.lastCookedDay = 12
        repository.history.send([testSummary(3), two, one])
        let preferences = FakeAppPreferences(recipeSort: .recentlyCooked)
        let on = RecipesViewModel(repository: repository, preferences: preferences, sleep: immediateSleep, cookedSort: true)
        let off = RecipesViewModel(repository: repository, preferences: preferences, sleep: immediateSleep)
        await settleMain()
        XCTAssertEqual(on.uiState.recipes?.map(\.id), [1, 2, 3])
        XCTAssertEqual(off.uiState.sort, .recentlyViewed)
        XCTAssertEqual(off.uiState.recipes?.map(\.id), [3, 2, 1])
    }
}
