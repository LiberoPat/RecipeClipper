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

    /// Field first (#237): arms `field` (unless it is armed), selects `text` on the page, confirms.
    private func select(_ vm: ClipViewModel, _ text: String, _ field: ClipField) {
        if vm.uiState.armed != field { vm.onFieldButton(field) }
        vm.onSelectionChanged(text)
        vm.onConfirm()
    }

    func testThePageIsTheCleanedLink() {
        let vm = viewModel()
        XCTAssertEqual(vm.uiState.url, cleaned)
        XCTAssertEqual(vm.uiState.draft.sourceUrl, cleaned)
        XCTAssertNil(vm.uiState.notice)
    }

    func testThePageLoadsFromWhereTheLinkPointsExceptOtherRedditHostsWhichLoadFromWww() {
        XCTAssertEqual(viewModel().uiState.pageUrl, cleaned)
        let old = "https://old.reddit.com/r/recipes/comments/1abc01/apple_pie/"
        let vm = ClipViewModel(url: old, repository: repository, drafts: store, readBlocked: true)
        XCTAssertEqual(vm.uiState.pageUrl, "https://www.reddit.com/r/recipes/comments/1abc01/apple_pie/")
        // The clip is still saved under the link that was shared.
        XCTAssertEqual(vm.uiState.url, old)
        XCTAssertEqual(vm.uiState.draft.sourceUrl, old)
    }

    func testASelectionIsPreviewedAsTheLinesItWouldBecome() {
        let vm = viewModel()
        vm.onSelectionChanged("1 cup flour\n\n 2 eggs ")
        XCTAssertEqual(vm.uiState.selection, ["1 cup flour", "2 eggs"])
    }

    // MARK: Field first (#237)

    func testItOpensWithNothingArmedPointingToName() {
        let vm = viewModel()
        XCTAssertNil(vm.uiState.armed)
        XCTAssertEqual(vm.uiState.hint, .next(added: nil, next: .name))
    }

    func testAFieldButtonArmsItsFieldAgainDisarmsItAndAnotherSwitches() {
        let vm = viewModel()
        vm.onFieldButton(.ingredients)
        XCTAssertEqual(vm.uiState.armed, .ingredients)
        XCTAssertEqual(vm.uiState.hint, .select(.ingredients))
        vm.onFieldButton(.steps)
        XCTAssertEqual(vm.uiState.armed, .steps)
        vm.onFieldButton(.steps)
        XCTAssertNil(vm.uiState.armed)
    }

    func testASelectionWhileArmedOffersTheConfirmAndTheConfirmAddsIt() {
        let vm = viewModel()
        vm.onFieldButton(.ingredients)
        vm.onSelectionChanged("1 cup flour\n2 eggs\n1 tsp salt")
        XCTAssertEqual(vm.uiState.hint, .confirm(.ingredients, lines: 3))
        // Nothing is added before the confirm.
        XCTAssertTrue(vm.uiState.draft.isEmpty)

        vm.onConfirm()
        XCTAssertEqual(vm.uiState.draft.ingredients, ["1 cup flour", "2 eggs", "1 tsp salt"])
        XCTAssertEqual(vm.uiState.newMarkId, "m1")
        XCTAssertEqual(vm.uiState.selection, [])
        // Ingredients stay armed for the next block; the hint says what was added and what's next.
        XCTAssertEqual(vm.uiState.armed, .ingredients)
        XCTAssertEqual(vm.uiState.hint, .next(added: ClipAdded(field: .ingredients, count: 3), next: .name))
        // The words are in the hint bar, not a snackbar.
        XCTAssertNil(vm.uiState.notice)
    }

    func testANameConfirmsAsOneLineReplacesAndDisarms() {
        let vm = viewModel()
        vm.onFieldButton(.name)
        vm.onSelectionChanged("Brown Butter\nOat Cookies")
        XCTAssertEqual(vm.uiState.hint, .confirm(.name, lines: 1))
        vm.onConfirm()
        XCTAssertEqual(vm.uiState.draft.name, "Brown Butter Oat Cookies")
        XCTAssertNil(vm.uiState.armed)

        select(vm, "Oat Cookies", .name)
        XCTAssertEqual(vm.uiState.draft.name, "Oat Cookies")
        XCTAssertEqual(vm.uiState.draft.marks.map(\.id), ["m2"])
    }

    func testIngredientsAndStepsAddUpAcrossSeparateBlocks() {
        let vm = viewModel()
        select(vm, "1 cup flour\n2 eggs", .ingredients)
        select(vm, "For the glaze:\n1 cup sugar", .ingredients)
        select(vm, "Mix.", .steps)
        select(vm, "Bake.", .steps)

        XCTAssertEqual(vm.uiState.draft.ingredients, ["1 cup flour", "2 eggs", "For the glaze:", "1 cup sugar"])
        XCTAssertEqual(vm.uiState.draft.steps, ["Mix.", "Bake."])
        XCTAssertEqual(vm.uiState.draft.marks.map(\.id), ["m1", "m2", "m3", "m4"])
        XCTAssertEqual(vm.uiState.hint, .next(added: ClipAdded(field: .steps, count: 1), next: .name))
    }

    func testSwitchingFieldsKeepsTheSelectionForTheNewOne() {
        let vm = viewModel()
        vm.onFieldButton(.steps)
        vm.onSelectionChanged("1 cup flour\n2 eggs")
        vm.onFieldButton(.ingredients)
        XCTAssertEqual(vm.uiState.hint, .confirm(.ingredients, lines: 2))
        vm.onConfirm()
        XCTAssertEqual(vm.uiState.draft.ingredients, ["1 cup flour", "2 eggs"])
        XCTAssertEqual(vm.uiState.draft.steps, [])
    }

    func testDisarmingOrClearDropsTheSelectionAndTellsThePage() {
        let vm = viewModel()
        vm.onFieldButton(.steps)
        vm.onSelectionChanged("Mix.")
        let cleared = vm.uiState.clearSelection

        vm.onClearSelection()
        XCTAssertEqual(vm.uiState.selection, [])
        XCTAssertEqual(vm.uiState.armed, .steps)
        XCTAssertEqual(vm.uiState.clearSelection, cleared + 1)

        vm.onSelectionChanged("Bake.")
        vm.onFieldButton(.steps)
        XCTAssertNil(vm.uiState.armed)
        XCTAssertEqual(vm.uiState.selection, [])
        XCTAssertEqual(vm.uiState.clearSelection, cleared + 2)
        vm.onConfirm()
        XCTAssertTrue(vm.uiState.draft.isEmpty)
    }

    func testTextSelectedWithNothingArmedAsksForTheFieldWhichThenConfirms() {
        let vm = viewModel()
        vm.onSelectionChanged("Mix.\nBake.")
        XCTAssertEqual(vm.uiState.hint, .selected(lines: 2))
        vm.onConfirm()
        XCTAssertTrue(vm.uiState.draft.isEmpty)

        vm.onFieldButton(.steps)
        XCTAssertEqual(vm.uiState.hint, .confirm(.steps, lines: 2))
        vm.onConfirm()
        XCTAssertEqual(vm.uiState.draft.steps, ["Mix.", "Bake."])
    }

    func testConfirmingWithNothingSelectedDoesNothing() {
        let vm = viewModel()
        vm.onConfirm()
        vm.onFieldButton(.steps)
        vm.onConfirm()
        vm.onSelectionChanged(" \n ")
        vm.onConfirm()
        XCTAssertTrue(vm.uiState.draft.isEmpty)
        XCTAssertNil(vm.uiState.lastAdded)
    }

    func testTheSelectionIsUsedOnce() {
        let vm = viewModel()
        select(vm, "Mix.", .steps)
        vm.onFieldButton(.ingredients)
        vm.onConfirm()
        XCTAssertEqual(vm.uiState.draft.ingredients, [])
    }

    func testTheHintWalksThroughTheFieldsThenSaysToTapDone() {
        let vm = viewModel()
        vm.onFieldButton(.photo)
        vm.onImageTapped("https://img.example/cookies.jpg")
        // The owner's example: "Photo added. Next: tap Name".
        XCTAssertEqual(vm.uiState.hint, .next(added: ClipAdded(field: .photo, count: 1), next: .name))

        select(vm, "Cookies", .name)
        XCTAssertEqual(vm.uiState.hint, .next(added: ClipAdded(field: .name, count: 1), next: .ingredients))
        select(vm, "flour\nsugar", .ingredients)
        XCTAssertEqual(vm.uiState.hint, .next(added: ClipAdded(field: .ingredients, count: 2), next: .steps))
        select(vm, "Bake.", .steps)
        XCTAssertEqual(vm.uiState.hint, .next(added: ClipAdded(field: .steps, count: 1), next: nil))

        // Arming another field moves on from what was added.
        vm.onFieldButton(.name)
        XCTAssertEqual(vm.uiState.hint, .select(.name))
    }

    func testWithoutAPhotoTheHintPointsToItLast() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "flour", .ingredients)
        select(vm, "Bake.", .steps)
        XCTAssertEqual(vm.uiState.hint, .next(added: ClipAdded(field: .steps, count: 1), next: .photo))
    }

    func testUndoTakesBackEachAddInTurn() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "flour\nsugar", .ingredients)
        select(vm, "butter", .ingredients)

        vm.onUndo()
        XCTAssertEqual(vm.uiState.draft.ingredients, ["flour", "sugar"])
        XCTAssertEqual(vm.uiState.draft.marks.map(\.id), ["m1", "m2"])
        XCTAssertNil(vm.uiState.newMarkId)
        XCTAssertNil(vm.uiState.lastAdded)

        vm.onUndo()
        XCTAssertEqual(vm.uiState.draft.ingredients, [])
        vm.onUndo()
        XCTAssertTrue(vm.uiState.draft.isEmpty)
        vm.onUndo()
        XCTAssertTrue(vm.uiState.draft.isEmpty)
    }

    func testTappingAnAddsTagTakesBackJustThatAddWithUndo() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "Mix.\nBake.", .steps)
        select(vm, "Cool.", .steps)

        vm.onTagTapped("m2")
        XCTAssertEqual(vm.uiState.draft.steps, ["Cool."])
        XCTAssertEqual(vm.uiState.draft.name, "Cookies")
        XCTAssertEqual(vm.uiState.notice?.message, .removed(.steps, count: 2))

        vm.onUndo()
        XCTAssertEqual(vm.uiState.draft.steps, ["Mix.", "Bake.", "Cool."])
    }

    func testATagTheDraftNoLongerHoldsDoesNothing() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        vm.onTagTapped("m9")
        XCTAssertEqual(vm.uiState.draft.name, "Cookies")
        XCTAssertNil(vm.uiState.notice)
    }

    func testThePhotoIsTheNextImageTappedAfterThePhotoButtonAndOnlyThat() {
        let vm = viewModel()
        vm.onImageTapped("https://img.example/ad.jpg")
        XCTAssertNil(vm.uiState.draft.photo)

        vm.onFieldButton(.photo)
        XCTAssertTrue(vm.uiState.pickingPhoto)
        XCTAssertEqual(vm.uiState.hint, .pickPhoto)
        vm.onImageTapped("https://img.example/cookies.jpg")
        vm.onImageTapped("https://img.example/ad.jpg")

        XCTAssertEqual(vm.uiState.draft.photo, "https://img.example/cookies.jpg")
        XCTAssertFalse(vm.uiState.pickingPhoto)
        XCTAssertEqual(vm.uiState.lastAdded, ClipAdded(field: .photo, count: 1))
    }

    func testThePhotoButtonDisarmsPhotoPickingAgain() {
        let vm = viewModel()
        vm.onFieldButton(.photo)
        vm.onFieldButton(.photo)
        XCTAssertFalse(vm.uiState.pickingPhoto)
    }

    func testArmingThePhotoDropsATextSelectionWhichItCantUse() {
        let vm = viewModel()
        vm.onFieldButton(.steps)
        vm.onSelectionChanged("Mix.")
        vm.onFieldButton(.photo)
        XCTAssertEqual(vm.uiState.selection, [])
        XCTAssertEqual(vm.uiState.hint, .pickPhoto)
    }

    // The owner's "stuck in the photo section": a tap on a picture the page can't give an
    // address for left picking on, every later tap swallowed and the other fields waiting.

    func testATapWithNoReadablePictureEndsPickingSaysSoAndTheOtherFieldsGoOn() {
        let vm = viewModel()
        vm.onFieldButton(.photo)
        vm.onNoImageTapped()

        XCTAssertFalse(vm.uiState.pickingPhoto)
        XCTAssertNil(vm.uiState.draft.photo)
        XCTAssertEqual(vm.uiState.notice?.message, .photoUnreadable)

        select(vm, "Brown Butter Oat Cookies", .name)
        XCTAssertEqual(vm.uiState.draft.name, "Brown Butter Oat Cookies")
    }

    func testATapWithNoReadablePictureKeepsThePhotoThereWas() {
        let vm = viewModel()
        vm.onFieldButton(.photo)
        vm.onImageTapped("https://img.example/cookies.jpg")
        vm.onFieldButton(.photo)
        vm.onNoImageTapped()
        XCTAssertEqual(vm.uiState.draft.photo, "https://img.example/cookies.jpg")
        XCTAssertFalse(vm.uiState.pickingPhoto)
    }

    func testANoImageTapWhenNotPickingSaysNothing() {
        let vm = viewModel()
        vm.onNoImageTapped()
        XCTAssertNil(vm.uiState.notice)
    }

    func testSkipLeavesThePhotoStepWithoutAPhoto() {
        let vm = viewModel()
        vm.onFieldButton(.photo)
        vm.onSkipPhoto()
        XCTAssertFalse(vm.uiState.pickingPhoto)
        XCTAssertNil(vm.uiState.draft.photo)
        XCTAssertNil(vm.uiState.notice)
    }

    func testSelectingTextALongPressWhilePickingMovesOnFromThePhoto() {
        let vm = viewModel()
        vm.onFieldButton(.photo)
        vm.onSelectionChanged("1 cup flour\n2 eggs")
        XCTAssertFalse(vm.uiState.pickingPhoto)

        vm.onFieldButton(.ingredients)
        vm.onConfirm()
        XCTAssertEqual(vm.uiState.draft.ingredients, ["1 cup flour", "2 eggs"])
        XCTAssertFalse(vm.uiState.pickingPhoto)
    }

    func testASelectionClearedWhilePickingLeavesPickingOn() {
        let vm = viewModel()
        vm.onSelectionChanged("Brown Butter")
        vm.onFieldButton(.photo)
        // Arming the photo dropped the selection; the page reports it gone.
        vm.onSelectionChanged("")
        XCTAssertTrue(vm.uiState.pickingPhoto)
    }

    func testTappingATagWhilePickingEndsPicking() {
        let vm = viewModel()
        select(vm, "Brown Butter", .name)
        vm.onFieldButton(.photo)
        vm.onTagTapped("m1")
        XCTAssertFalse(vm.uiState.pickingPhoto)
        XCTAssertEqual(vm.uiState.notice?.message, .removed(.name, count: 1))
    }
    func testDoneOpensReviewOnceThereIsANamePlusIngredientsOrSteps() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "Bake.", .steps)
        vm.onReview()
        XCTAssertTrue(vm.uiState.reviewing)

        vm.onBackToPage()
        XCTAssertFalse(vm.uiState.reviewing)
    }

    // The owner's S23 (#213): ingredients and steps selected on a Reddit post, no name (its title
    // is hard to select there), and Done did nothing, greyed out, with nothing saying why.
    func testDoneWithLinesButNoNameOpensReviewSayingToTypeTheName() {
        let vm = viewModel()
        select(vm, "flour\nsugar", .ingredients)
        select(vm, "Mix.\nBake.", .steps)
        vm.onReview()
        XCTAssertTrue(vm.uiState.reviewing)
        XCTAssertEqual(vm.uiState.notice?.message, .missing(name: true, lines: false))
    }

    func testDoneWithANameButNoLinesStaysOnThePageSayingWhatToSelect() {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        vm.onReview()
        XCTAssertFalse(vm.uiState.reviewing)
        XCTAssertEqual(vm.uiState.notice?.message, .missing(name: false, lines: true))
    }

    func testDoneWithNothingYetSaysEverythingIsMissing() {
        let vm = viewModel()
        vm.onReview()
        XCTAssertFalse(vm.uiState.reviewing)
        XCTAssertEqual(vm.uiState.notice?.message, .missing(name: true, lines: true))
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
        vm.onFieldButton(.photo)
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

    func testSavingWithoutANameSavesNothingAndSaysToTypeIt() async {
        let vm = viewModel()
        select(vm, "flour", .ingredients)
        vm.onReview()
        vm.onNameChange("  ")
        vm.onSave()
        await settleMain()
        XCTAssertTrue(repository.saveClipCalls.isEmpty)
        XCTAssertEqual(vm.uiState.notice?.message, .missing(name: true, lines: false))
        XCTAssertFalse(vm.uiState.saving)

        // Typed in Review, the name is enough.
        vm.onNameChange("Cookies")
        vm.onSave()
        await settleMain()
        XCTAssertEqual(repository.saveClipCalls.map(\.name), ["Cookies"])
        XCTAssertEqual(vm.uiState.savedRecipeId, 1)
    }

    func testSavingWithTheLinesBlankedInReviewSaysIngredientsOrStepsAreMissing() async {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "flour", .ingredients)
        vm.onReview()
        vm.onLineChange(.ingredients, 0, " ")
        vm.onSave()
        await settleMain()
        XCTAssertTrue(repository.saveClipCalls.isEmpty)
        XCTAssertEqual(vm.uiState.notice?.message, .missing(name: false, lines: true))
    }

    func testAFullLibrarySaysSoRatherThanSavingNothingSilently() async throws {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "flour", .ingredients)
        repository.saveClipResult = .notKept(try XCTUnwrap(vm.uiState.draft.toRecipe()))
        vm.onSave()
        await settleMain()
        XCTAssertTrue(vm.uiState.libraryFull)
        XCTAssertNil(vm.uiState.savedRecipeId)
        XCTAssertFalse(vm.uiState.saving)
    }

    func testARedditShareLinkSavesUnderTheLinkThatWasSharedNotThePageItLoadsFrom() async {
        let shareLink = "https://www.reddit.com/r/recipes/s/AbCdEf123"
        let vm = ClipViewModel(url: shareLink, repository: repository, drafts: store, readBlocked: true)
        select(vm, "Soup", .name)
        select(vm, "water", .ingredients)
        vm.onSave()
        await settleMain()
        XCTAssertEqual(repository.saveClipCalls.map(\.sourceUrl), [shareLink])
        XCTAssertEqual(vm.uiState.savedRecipeId, 1)
    }

    // MARK: The Text view (#213)

    private let post = "https://www.reddit.com/r/recipes/comments/1abc01/apple_pie/"
    private let postHtml = """
        <shreddit-post post-title="Apple Pie"><div slot="text-body"><div property="schema:articleBody">
          <ul><li><p>3 apples</p></li><li><p>1 crust</p></li></ul><p>Bake.</p>
        </div></div></shreddit-post>
        <shreddit-comment author="baker" depth="0"><div slot="comment"><p>Use 4 apples.</p></div></shreddit-comment>
        """

    private func redditViewModel() -> ClipViewModel {
        ClipViewModel(url: post, repository: repository, drafts: store, readBlocked: true)
    }

    func testOnlyARedditPostOffersTheTextView() {
        let blog = viewModel()
        XCTAssertFalse(blog.uiState.offersText)
        blog.onShowText()
        XCTAssertFalse(blog.uiState.readingText)
        XCTAssertTrue(redditViewModel().uiState.offersText)
    }

    func testTheTextViewReadsThePageThenShowsThePostAsText() {
        let vm = redditViewModel()
        vm.onSelectionChanged("something on the page")
        vm.onShowText()
        XCTAssertTrue(vm.uiState.readingText)
        XCTAssertEqual(vm.uiState.selection, [])

        vm.onPageText(postHtml)
        let state = vm.uiState
        XCTAssertFalse(state.readingText)
        XCTAssertTrue(state.showingText)
        XCTAssertEqual(state.pageText?.title, "Apple Pie")
        XCTAssertEqual(state.pageText?.body, ["3 apples", "1 crust", "Bake."])
        XCTAssertEqual(state.pageText?.comments.first?.lines, ["Use 4 apples."])
    }

    func testTextSelectedInTheTextViewIsAssignedAndSavedLikeThePages() async throws {
        let vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)

        select(vm, "Apple Pie", .name)
        XCTAssertEqual(vm.uiState.newMarkId, "m1")
        select(vm, "3 apples\n1 crust", .ingredients)
        select(vm, "Bake.", .steps)
        vm.onReview()
        vm.onSave()
        await settleMain()

        let saved = try XCTUnwrap(repository.saveClipCalls.first)
        XCTAssertEqual(saved.name, "Apple Pie")
        XCTAssertEqual(saved.ingredients, ["3 apples", "1 crust"])
        XCTAssertEqual(saved.instructions, ["Bake."])
        XCTAssertEqual(saved.sourceUrl, post)
    }

    func testBackToThePageKeepsTheTextAndTheDraft() {
        let vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        select(vm, "Apple Pie", .name)
        vm.onShowPage()
        XCTAssertFalse(vm.uiState.showingText)
        XCTAssertEqual(vm.uiState.pageText?.title, "Apple Pie")
        XCTAssertEqual(vm.uiState.draft.name, "Apple Pie")
    }

    // The owner (#237): "when toggling a reddit page from text back to page, page doesnt work".
    // Page, Text, Page: the page takes the armed field's selections again, as before.
    func testPageThenTextThenPageAgainTheArmedFieldCarriesOverAndThePageWorksAsBefore() {
        let vm = redditViewModel()
        vm.onFieldButton(.ingredients)
        vm.onSelectionChanged("on the page")
        let cleared = vm.uiState.clearSelection

        vm.onShowText()
        vm.onPageText(postHtml)
        XCTAssertEqual(vm.uiState.armed, .ingredients)
        XCTAssertEqual(vm.uiState.clearSelection, cleared + 1)
        vm.onSelectionChanged("3 apples\n1 crust")
        vm.onConfirm()

        vm.onShowPage()
        let state = vm.uiState
        XCTAssertFalse(state.showingText)
        XCTAssertFalse(state.readingText)
        XCTAssertEqual(state.armed, .ingredients)
        XCTAssertEqual(state.selection, [])
        XCTAssertNil(state.newMarkId)
        XCTAssertEqual(state.clearSelection, cleared + 2)

        // The page's next selection goes in as the Text view's did.
        vm.onSelectionChanged("1 tsp cinnamon")
        XCTAssertEqual(vm.uiState.hint, .confirm(.ingredients, lines: 1))
        vm.onConfirm()
        XCTAssertEqual(vm.uiState.draft.ingredients, ["3 apples", "1 crust", "1 tsp cinnamon"])
        XCTAssertEqual(vm.uiState.newMarkId, "m2")
    }

    func testTheTextViewNeverKeepsPhotoPicking() {
        let vm = redditViewModel()
        vm.onFieldButton(.photo)
        vm.onShowText()
        XCTAssertFalse(vm.uiState.pickingPhoto)
    }

    func testAPageWithNoPostYetSaysSoAndStaysOnThePage() {
        let vm = redditViewModel()
        vm.onShowText()
        vm.onPageText("<html><body><p>Checking your browser</p></body></html>")
        XCTAssertFalse(vm.uiState.showingText)
        XCTAssertFalse(vm.uiState.readingText)
        XCTAssertEqual(vm.uiState.notice?.message, .textUnreadable)
    }

    func testPickingThePhotoGoesBackToThePage() {
        let vm = redditViewModel()
        vm.onShowText()
        vm.onPageText(postHtml)
        vm.onFieldButton(.photo)
        XCTAssertTrue(vm.uiState.pickingPhoto)
        XCTAssertFalse(vm.uiState.showingText)
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
        vm.onTagTapped("m1")
        XCTAssertNil(store.get(cleaned))
    }

    func testAShownNoticeIsClearedOnlyByItsOwnSerial() throws {
        let vm = viewModel()
        select(vm, "Cookies", .name)
        select(vm, "Mix.", .steps)
        vm.onTagTapped("m1")
        let first = try XCTUnwrap(vm.uiState.notice)
        vm.onTagTapped("m2")
        vm.onNoticeShown(first.serial)
        XCTAssertEqual(vm.uiState.notice?.message, .removed(.steps, count: 1))
        vm.onNoticeShown(try XCTUnwrap(vm.uiState.notice).serial)
        XCTAssertNil(vm.uiState.notice)
    }

    func testTheSyncStateCarriesTheMarksTheDraftHolds() throws {
        var draft = ClipDraft(sourceUrl: cleaned).assign(.name, "Cookies").assign(.ingredients, "a\nb")
        draft = draft.assign(.photo, "https://img.example/c.jpg")
        draft = draft.assign(.ingredients, "c")
        let json = ClipScreen.syncJson(draft, newMarkId: "m2", armed: .steps, clear: 3)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        let marks = try XCTUnwrap(object["marks"] as? [[String: String]])
        // Each add has its own mark and tag (#237).
        XCTAssertEqual(marks, [
            ["id": "m1", "field": "NAME", "label": "Name"],
            ["id": "m2", "field": "INGREDIENTS", "label": "Ingredients · 2"],
            ["id": "m4", "field": "INGREDIENTS", "label": "Ingredients · 1"],
        ])
        XCTAssertEqual(object["newId"] as? String, "m2")
        XCTAssertEqual(object["armed"] as? String, "STEPS")
        XCTAssertEqual(object["clear"] as? Int, 3)
        XCTAssertEqual((object["photo"] as? [String: String])?["src"], "https://img.example/c.jpg")
        XCTAssertEqual((object["photo"] as? [String: String])?["id"], "m3")
        XCTAssertTrue(ClipScreen.syncJson(draft, newMarkId: nil).contains("\"armed\":null"))
    }
}
