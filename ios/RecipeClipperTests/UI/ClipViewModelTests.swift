import XCTest
@testable import RecipeClipper

/// Mirrors Android's ClipViewModelTest, case for case.
@MainActor
final class ClipViewModelTests: XCTestCase {

    private let shared = "https://www.hearthandcrumb.example/cookies?utm_source=x#jump"
    private let cleaned = "https://www.hearthandcrumb.example/cookies"

    private var repository: FakeRecipeRepository!
    private var store: ClipDraftStore!

    override func setUp() async throws {
        repository = FakeRecipeRepository()
        store = ClipDraftStore()
    }

    private func viewModel(_ url: String? = nil) -> ClipViewModel {
        ClipViewModel(url: url ?? shared, repository: repository, drafts: store)
    }

    private func select(_ vm: ClipViewModel, _ text: String, _ field: ClipField) {
        vm.onSelectionChanged(text)
        vm.onAssign(field)
    }

    func testThePageIsTheCleanedLink() {
        let vm = viewModel()
        XCTAssertEqual(vm.uiState.url, cleaned)
        XCTAssertEqual(vm.uiState.draft.sourceUrl, cleaned)
        XCTAssertNil(vm.uiState.notice)
    }

    func testASelectionIsPreviewedAsTheLinesItWouldBecome() {
        let vm = viewModel()
        vm.onSelectionChanged("1 cup flour\n\n 2 eggs ")
        XCTAssertEqual(vm.uiState.selection, ["1 cup flour", "2 eggs"])
    }

    func testAssigningReplacesTheFieldMarksItAndSaysHowMany() {
        let vm = viewModel()
        select(vm, "a\nb\nc\nd\ne\nf\ng\nh", .ingredients)
        select(vm, "1\n2\n3\n4", .ingredients)

        XCTAssertEqual(vm.uiState.draft.ingredients, ["1", "2", "3", "4"])
        XCTAssertEqual(vm.uiState.notice?.message, .assigned(.ingredients, count: 4))
        XCTAssertEqual(vm.uiState.newMarkId, "m2")
        XCTAssertEqual(vm.uiState.selection, [])
    }

    func testANameJoinsTheSelectedLines() {
        let vm = viewModel()
        select(vm, "Brown Butter\nOat Cookies", .name)
        XCTAssertEqual(vm.uiState.draft.name, "Brown Butter Oat Cookies")
    }

    func testAssigningWithNothingSelectedDoesNothing() {
        let vm = viewModel()
        vm.onAssign(.steps)
        select(vm, " \n ", .steps)
        XCTAssertTrue(vm.uiState.draft.isEmpty)
        XCTAssertNil(vm.uiState.notice)
    }

    func testTheSelectionIsUsedOnce() {
        let vm = viewModel()
        select(vm, "Mix.", .steps)
        vm.onAssign(.ingredients)
        XCTAssertEqual(vm.uiState.draft.ingredients, [])
    }

    func testUndoPutsBackTheDraftBeforeTheLastAssignmentOnce() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "flour\nsugar", .ingredients)
        select(vm, "butter", .ingredients)

        vm.onUndo()
        XCTAssertEqual(vm.uiState.draft.ingredients, ["flour", "sugar"])
        XCTAssertEqual(vm.uiState.draft.marks[.ingredients], "m2")
        XCTAssertNil(vm.uiState.newMarkId)

        vm.onUndo()
        XCTAssertEqual(vm.uiState.draft.ingredients, ["flour", "sugar"])
    }

    func testTappingATagClearsThatFieldWithUndo() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "Mix.\nBake.", .steps)

        vm.onTagTapped(.steps)
        XCTAssertEqual(vm.uiState.draft.steps, [])
        XCTAssertEqual(vm.uiState.draft.name, "Cookies")
        XCTAssertEqual(vm.uiState.notice?.message, .cleared(.steps))

        vm.onUndo()
        XCTAssertEqual(vm.uiState.draft.steps, ["Mix.", "Bake."])
    }

    func testThePhotoIsTheNextImageTappedAfterThePhotoButtonAndOnlyThat() {
        let vm = viewModel()
        vm.onImageTapped("https://img.example/ad.jpg")
        XCTAssertNil(vm.uiState.draft.photo)

        vm.onPhotoButton()
        XCTAssertTrue(vm.uiState.pickingPhoto)
        vm.onImageTapped("https://img.example/cookies.jpg")
        vm.onImageTapped("https://img.example/ad.jpg")

        XCTAssertEqual(vm.uiState.draft.photo, "https://img.example/cookies.jpg")
        XCTAssertFalse(vm.uiState.pickingPhoto)
        XCTAssertEqual(vm.uiState.notice?.message, .assigned(.photo, count: 1))
    }

    func testThePhotoButtonTogglesPickingOffAgain() {
        let vm = viewModel()
        vm.onPhotoButton()
        vm.onPhotoButton()
        XCTAssertFalse(vm.uiState.pickingPhoto)
    }

    func testReviewOpensOnlyOnceThereIsANamePlusIngredientsOrSteps() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        vm.onReview()
        XCTAssertFalse(vm.uiState.reviewing)

        select(vm, "Bake.", .steps)
        vm.onReview()
        XCTAssertTrue(vm.uiState.reviewing)

        vm.onBackToPage()
        XCTAssertFalse(vm.uiState.reviewing)
    }

    func testReviewEditsLinesAndTakesTypedServesAndTime() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "flour\nsugar", .ingredients)
        vm.onReview()

        vm.onLineChange(.ingredients, 1, "brown sugar")
        vm.onLineAdd(.ingredients)
        vm.onLineChange(.ingredients, 2, "2 eggs")
        vm.onLineRemove(.ingredients, 0)
        vm.onNameChange("Oat Cookies")
        vm.onServesChange("24 cookies")
        vm.onTotalTimeChange("45 min")

        let draft = vm.uiState.draft
        XCTAssertEqual(draft.ingredients, ["brown sugar", "2 eggs"])
        XCTAssertEqual(draft.name, "Oat Cookies")
        XCTAssertEqual(draft.serves, "24 cookies")
        XCTAssertEqual(draft.totalTime, "45 min")
        XCTAssertTrue(vm.uiState.reviewing)
    }

    func testSavingWritesTheRecipeDropsTheDraftAndOpensIt() async throws {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "flour", .ingredients)
        vm.onPhotoButton()
        vm.onImageTapped("https://img.example/c.jpg")
        vm.onReview()
        vm.onServesChange("24")

        vm.onSave()
        await settleMain()

        let saved = try XCTUnwrap(repository.saveClipCalls.first)
        XCTAssertEqual(repository.saveClipCalls.count, 1)
        XCTAssertEqual(saved.name, "Cookies")
        XCTAssertEqual(saved.ingredients, ["flour"])
        XCTAssertEqual(saved.image, "https://img.example/c.jpg")
        XCTAssertEqual(saved.yield, "24")
        XCTAssertEqual(saved.sourceUrl, cleaned)
        XCTAssertEqual(vm.uiState.savedRecipeId, 1)
        XCTAssertNil(store.get(cleaned))
    }

    func testAFailedSaveKeepsTheDraftAndSaysSo() async {
        repository.saveClipResult = .error(.saveFailed)
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "flour", .ingredients)

        vm.onSave()
        await settleMain()

        XCTAssertEqual(vm.uiState.notice?.message, .saveFailed)
        XCTAssertNil(vm.uiState.savedRecipeId)
        XCTAssertFalse(vm.uiState.saving)
        XCTAssertEqual(store.get(cleaned)?.name, "Cookies")
    }

    func testSavingDoesNothingUntilItCanFinish() async {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        vm.onSave()
        await settleMain()
        XCTAssertTrue(repository.saveClipCalls.isEmpty)
    }

    func testLeavingKeepsTheDraftForTheSessionAndReopeningRestoresIt() {
        let first = viewModel()
        select(first, "Cookies", .name)
        select(first, "flour", .ingredients)

        let reopened = viewModel(cleaned)
        XCTAssertEqual(reopened.uiState.draft.name, "Cookies")
        XCTAssertEqual(reopened.uiState.draft.ingredients, ["flour"])
        XCTAssertEqual(reopened.uiState.notice?.message, .draftRestored)
    }

    func testADraftBelongsToItsPage() {
        select(viewModel(), "Cookies", .name)
        let other = viewModel("https://other.example/pie")
        XCTAssertTrue(other.uiState.draft.isEmpty)
        XCTAssertNil(other.uiState.notice)
    }

    func testDiscardStartsOverAndForgetsTheDraft() {
        select(viewModel(), "Cookies", .name)
        let reopened = viewModel()
        reopened.onDiscardDraft()
        XCTAssertTrue(reopened.uiState.draft.isEmpty)
        XCTAssertNil(store.get(cleaned))
        XCTAssertTrue(viewModel().uiState.draft.isEmpty)
    }

    func testClearingEverythingLeavesNoDraftBehind() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        vm.onTagTapped(.name)
        XCTAssertNil(store.get(cleaned))
    }

    func testAShownNoticeIsClearedOnlyByItsOwnSerial() throws {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        let first = try XCTUnwrap(vm.uiState.notice)
        select(vm, "Mix.", .steps)
        vm.onNoticeShown(first.serial)
        XCTAssertEqual(vm.uiState.notice?.message, .assigned(.steps, count: 1))
        vm.onNoticeShown(try XCTUnwrap(vm.uiState.notice).serial)
        XCTAssertNil(vm.uiState.notice)
    }

    func testTheSyncStateCarriesTheMarksTheDraftHolds() throws {
        var draft = ClipDraft(sourceUrl: cleaned).assign(.name, "Cookies").assign(.ingredients, "a\nb")
        draft = draft.assign(.photo, "https://img.example/c.jpg")
        let json = ClipScreen.syncJson(draft, newMarkId: "m2")
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        let marks = try XCTUnwrap(object["marks"] as? [[String: String]])
        XCTAssertEqual(marks, [
            ["id": "m1", "field": "NAME", "label": "Name"],
            ["id": "m2", "field": "INGREDIENTS", "label": "Ingredients · 2"],
        ])
        XCTAssertEqual(object["newId"] as? String, "m2")
        XCTAssertEqual((object["photo"] as? [String: String])?["src"], "https://img.example/c.jpg")
    }
}
