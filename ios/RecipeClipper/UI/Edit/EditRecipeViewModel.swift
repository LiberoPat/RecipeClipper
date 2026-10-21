import Foundation
import Observation

/// How reading a post's photo (#198) went, for the line above the editor.
enum PhotoOutcome: Equatable {
    /// The splitter sorted the lines into ingredients and steps: check them.
    case read
    /// No text, or none the splitter could sort: finish it by hand.
    case notSorted
    /// No picture could be fetched or read: try again.
    case failed
}

/// The strip of a picture a line was read from (#286): `image`, a post's picture (a web link, from
/// the image cache the read filled) or a scan's page (a file URL, never cached: `local`), cropped
/// to `box`. Android's `PhotoStrip`.
struct PhotoStrip: Hashable, Sendable {
    let image: String
    let box: PhotoBox
    let local: Bool
}

/// A line to check in the review (#198, #283, #286): `flag` as the sorter listed it, kept to the
/// line as it is now in the box (`LineFlag.raw`, which the card's field and the box both edit);
/// where that line is, line `index` of the steps box when `steps`, else of the ingredients box; and
/// the `strip` of the photo it was read from, nil when the reader didn't say where. Android's
/// `LineToCheck`.
struct LineToCheck: Equatable {
    var flag: LineFlag
    let steps: Bool
    var index: Int
    let strip: PhotoStrip?
}

/// `isNew` is "New recipe" from Home; otherwise the recipe `draft` was loaded from.
/// `showInvalid` is set by a Save that failed validation, so the rule is only pointed out once
/// the user has tried. `savedId` is set once the save lands, for the view to open it.
struct EditRecipeUiState: Equatable {
    let isNew: Bool
    var loading: Bool
    var draft = RecipeDraft()
    var showInvalid = false
    var saving = false
    var saveFailed = false
    /// The recipe to edit is gone (deleted elsewhere).
    var missing = false
    var savedId: Int64?
    /// A new recipe couldn't be saved: the free library is full and all protected (#107).
    var libraryFull = false
    /// A purchase from that prompt that is pending or failed; shown until the next edit.
    var unlockNotice: PurchaseOutcome?
    /// "Read the photo" (#198): the Reddit post whose photos fill this editor; nil otherwise.
    /// For a scan (#226) its pictures are the cook's pages, with no link and no title.
    var photo: PhotoPost?
    /// "Scan a recipe" (#226): the pages are the cook's own, saved as a typed-in recipe.
    var scan = false
    /// The photos are being fetched and read; the fields wait.
    var reading = false
    var photoOutcome: PhotoOutcome?
    /// The lines to check against the photo, as the sorter listed them (#198, #283), each with
    /// why: the photo was hard to read there, its amount holds a fraction look-alike (the
    /// `fractions` as one-tap replacements, #245) or looks too large (#281); each a card with its
    /// strip of the photo and its line as a field (#286).
    var checks: [LineToCheck] = []
    var fractions: [String] = PhotoTextSorter.fractions
    /// The picture shown large under the thumbnails, tapped open (#286); nil when none is.
    var openPage: Int?

    init(isNew: Bool, photo: PhotoPost? = nil, scan: Bool = false) {
        self.isNew = isNew
        self.photo = photo
        self.scan = scan
        loading = !isNew
    }

    /// "Check these lines: the photo was hard to read there" heads the list, unless every line is
    /// listed only because its amount looks too large: each of those says so itself (#281).
    var checkHeading: Bool { checks.contains { !$0.flag.largeOnly } }

    /// The `checks`' lines, each with why.
    var flags: [LineFlag] { checks.map(\.flag) }

    /// The pictures read, for "2 pages · 5 lines to check" over the cards (#286).
    var pageCount: Int { photo?.imageUrls.count ?? 0 }
}

/// Edits a recipe's content, or types a new one in (#29; Android's EditRecipeViewModel).
/// Opened with a recipe id from the recipe screen's overflow menu, or with none from Home's
/// "New recipe".
///
/// With a `photo` (#198) it is the review of a Reddit photo read on the device: the post's title
/// and first photo, and the lines read, sorted by the Reddit splitter into ingredients and
/// steps, or, when nothing sorts, all in the ingredients box to finish by hand. Nothing is saved
/// until the cook taps Save; then it is kept under the post's link as the user's version
/// (clipped, like #37's clips), so a re-share never replaces it.
///
/// With `scanPages` (#226) it is the review of a scan: photos the cook took, picked or shared
/// in, read the same way, in order. Saved as a typed-in recipe (manual, `manual:<uuid>`) in the
/// language its words say, with no picture: the pages aren't kept.
@MainActor
@Observable
final class EditRecipeViewModel {
    private(set) var uiState: EditRecipeUiState
    @ObservationIgnored private let recipeId: Int64?
    @ObservationIgnored private let photo: PhotoPost?
    /// A scan's pages (#226), file URLs in order; nil when this isn't a scan.
    @ObservationIgnored private let scanPages: [String]?
    /// What is read: the post's pictures, or the scan's pages.
    @ObservationIgnored private let pictures: PhotoPost?
    @ObservationIgnored private let repository: RecipeRepository
    @ObservationIgnored private let entitlements: Entitlements
    @ObservationIgnored private let photoReader: PhotoTextReader
    @ObservationIgnored private var readTask: Task<Void, Never>?
    /// The language the photo's lines were read in (#208), or nil when nothing was clear.
    @ObservationIgnored private var photoLanguage: String?

    init(
        recipeId: Int64?, repository: RecipeRepository, entitlements: Entitlements = UnavailableEntitlements(),
        photo: PhotoPost? = nil, photoReader: PhotoTextReader = UnavailablePhotoTextReader(),
        scanPages: [String]? = nil
    ) {
        self.entitlements = entitlements
        self.recipeId = recipeId.flatMap { $0 > 0 ? $0 : nil }
        self.photo = self.recipeId == nil ? photo : nil
        let pages = (scanPages ?? []).filter { !$0.isEmpty }
        self.scanPages = self.recipeId == nil && self.photo == nil && !pages.isEmpty ? pages : nil
        pictures = self.photo ?? self.scanPages.map { PhotoPost(url: "", title: "", imageUrls: $0) }
        self.repository = repository
        self.photoReader = photoReader
        uiState = EditRecipeUiState(isNew: self.recipeId == nil, photo: pictures, scan: self.scanPages != nil)
        if let id = self.recipeId {
            Task { [weak self, repository] in
                let recipe = await repository.open(id: id)
                guard let self else { return }
                uiState.loading = false
                if let recipe {
                    uiState.draft = RecipeDraft.of(recipe)
                } else {
                    uiState.missing = true
                }
            }
        } else if let pictures {
            readPhoto(pictures)
        }
    }

    deinit {
        // Leaving the screen ends a read still running; nothing it finds is kept.
        readTask?.cancel()
    }

    /// "Try again" after the photos couldn't be fetched or read.
    func onReadAgain() {
        guard let pictures, !uiState.reading else { return }
        readPhoto(pictures)
    }

    private func readPhoto(_ post: PhotoPost) {
        readTask?.cancel()
        photoLanguage = nil
        uiState.reading = true
        uiState.photoOutcome = nil
        uiState.checks = []
        // A post's first picture is its photo; a scan's pages are never kept (#226).
        uiState.draft = RecipeDraft(name: post.title, image: scanPages != nil ? "" : post.imageUrls.first ?? "")
        readTask = Task { [weak self, photoReader] in
            let result = await photoReader.read(post.imageUrls)
            // Sorting and checking a long scan is real work: off the main actor (#283). A read
            // cancelled meanwhile (Try again, or the screen gone) is dropped below.
            var sorted: PhotoReading?
            if case .read(let lines) = result {
                sorted = await Task.detached(priority: .userInitiated) { PhotoTextSorter.sort(lines) }.value
            }
            guard let self, !Task.isCancelled else { return }
            uiState.reading = false
            switch result {
            case .failed:
                uiState.photoOutcome = .failed
            case .read:
                guard let reading = sorted else { return }
                photoLanguage = reading.language
                uiState.photoOutcome = reading.sorted ? .read : .notSorted
                uiState.checks = checks(of: reading, post: post)
                uiState.draft.yield = reading.yield ?? ""
                uiState.draft.prepTime = reading.prepTime ?? ""
                uiState.draft.cookTime = reading.cookTime ?? ""
                uiState.draft.totalTime = reading.totalTime ?? ""
                uiState.draft.ingredientsText = reading.ingredients.joined(separator: "\n")
                uiState.draft.instructionsText = reading.instructions.joined(separator: "\n")
            }
        }
    }

    /// The sorter's list as cards (#286): each line found in its box (the ingredients first, as
    /// listed), with the strip of the picture it was read from. Every listed line is a box's.
    private func checks(of reading: PhotoReading, post: PhotoPost) -> [LineToCheck] {
        reading.flags.compactMap { flag in
            let inIngredients = reading.ingredients.firstIndex(of: flag.raw)
            guard let index = inIngredients ?? reading.instructions.firstIndex(of: flag.raw) else { return nil }
            var strip: PhotoStrip?
            if let page = flag.page, post.imageUrls.indices.contains(page), let box = flag.box {
                strip = PhotoStrip(image: post.imageUrls[page], box: box, local: scanPages != nil)
            }
            return LineToCheck(flag: flag, steps: inIngredients == nil, index: index, strip: strip)
        }
    }

    /// A fraction chip (#245): `flag`'s look-alike ("%" in "2% cups oats") becomes `fraction`, the
    /// cook's reading of the photo, in the box that holds the line and in its card, which keeps any
    /// markup the line has ("▢ 2⅔ cups oats", #283). The card's chips go.
    func onFraction(_ flag: LineFlag, _ fraction: String) {
        guard let index = uiState.flags.firstIndex(of: flag) else { return }
        guard let fixed = PhotoTextSorter.replaceFraction(flag, fraction, words: RecipeTextSplitter.wordsFor(photoLanguage)) else {
            uiState.checks[index].flag.reasons.remove(.fraction)
            return
        }
        // The card is the box's line, so the box changes with it.
        var state = withLine(uiState, index, fixed.raw)
        if state.checks.indices.contains(index) { state.checks[index].flag = fixed }
        uiState = state
    }

    /// A card's field (#286): its line, typed over, in the box that holds it.
    func onCheckLine(_ index: Int, _ text: String) {
        uiState = withLine(uiState, index, text.replacingOccurrences(of: "\n", with: " "))
    }

    /// `state` with card `index`'s line in its box holding `line` instead.
    private func withLine(_ state: EditRecipeUiState, _ index: Int, _ line: String) -> EditRecipeUiState {
        guard state.checks.indices.contains(index) else { return state }
        let check = state.checks[index]
        var lines = (check.steps ? state.draft.instructionsText : state.draft.ingredientsText).components(separatedBy: "\n")
        guard lines.indices.contains(check.index) else { return state }
        lines[check.index] = line
        var draft = state.draft
        if check.steps { draft.instructionsText = lines.joined(separator: "\n") } else { draft.ingredientsText = lines.joined(separator: "\n") }
        return withDraft(state, draft)
    }

    func onDraftChange(_ draft: RecipeDraft) {
        uiState = withDraft(uiState, draft)
    }

    /// `state` with `draft` (#286): each card follows its line through the edit (`followLine`) and
    /// shows it as it now is (`PhotoTextSorter.edited`); a card whose line was edited away goes.
    private func withDraft(_ state: EditRecipeUiState, _ draft: RecipeDraft) -> EditRecipeUiState {
        let words = RecipeTextSplitter.wordsFor(photoLanguage)
        let old = (state.draft.ingredientsText.components(separatedBy: "\n"), state.draft.instructionsText.components(separatedBy: "\n"))
        let new = (draft.ingredientsText.components(separatedBy: "\n"), draft.instructionsText.components(separatedBy: "\n"))
        var next = state
        next.checks = state.checks.compactMap { check in
            let (before, after) = check.steps ? (old.1, new.1) : (old.0, new.0)
            guard let index = followLine(before, after, check.index), after.indices.contains(index) else { return nil }
            var moved = check
            moved.flag = PhotoTextSorter.edited(check.flag, after[index], words: words)
            moved.index = index
            return moved
        }
        next.draft = draft
        next.saveFailed = false
        next.unlockNotice = nil
        return next
    }

    /// A page's thumbnail (#286): shows that picture large under the row, or hides it again.
    func onPage(_ page: Int) {
        uiState.openPage = uiState.openPage == page ? nil : page
    }

    /// Unlock from the full-library prompt (#107), then save the recipe as typed.
    func onUnlock() {
        uiState.libraryFull = false
        Task { [weak self, entitlements] in
            let outcome = await entitlements.purchase()
            guard let self else { return }
            if outcome == .unlocked {
                onSave()
            } else if outcome.needsNotice {
                uiState.unlockNotice = outcome
            }
        }
    }

    func onLibraryFullDismiss() { uiState.libraryFull = false }

    func onSave() {
        let state = uiState
        guard !state.loading, !state.saving, !state.missing, !state.reading else { return }
        guard state.draft.isValid else {
            uiState.showInvalid = true
            return
        }
        uiState.saving = true
        uiState.saveFailed = false
        if let photo {
            savePhoto(photo, draft: state.draft)
            return
        }
        let language = scanPages != nil ? scannedLanguage(state.draft) : nil
        Task { [weak self, recipeId, repository, scanPages] in
            let saved: Recipe?
            if scanPages != nil {
                saved = await repository.addManual(draft: state.draft, language: language)
            } else if let recipeId {
                saved = await repository.saveEdit(id: recipeId, draft: state.draft)
            } else {
                saved = await repository.addManual(draft: state.draft)
            }
            guard let self else { return }
            uiState.saving = false
            if let saved, saved.id == 0 {
                uiState.libraryFull = true
            } else if let saved {
                uiState.savedId = saved.id
            } else {
                uiState.saveFailed = true
            }
        }
    }

    /// A scan's language (#226): its words (#208), then the checked recipe's, else English.
    private func scannedLanguage(_ draft: RecipeDraft) -> String {
        let content = draft.apply(to: Recipe(
            name: "", image: nil, ingredients: [], instructions: [], prepTime: nil, cookTime: nil,
            totalTime: nil, yield: nil, sourceUrl: ""
        ))
        let name = content.name, ingredients = content.ingredients
        return LanguageWords.resolve(declared: photoLanguage, page: nil) {
            LanguageWords.detectionText(name: name, ingredients: ingredients)
        }
    }

    /// The checked recipe, under the post's link, as the user's version (#198).
    private func savePhoto(_ post: PhotoPost, draft: RecipeDraft) {
        var recipe = draft.apply(to: Recipe(
            name: "", image: nil, ingredients: [], instructions: [], prepTime: nil, cookTime: nil,
            totalTime: nil, yield: nil, sourceUrl: post.url, sourceType: .reddit
        ))
        // Reddit declares no language: the photo's words (#208), then the checked recipe's,
        // decide, else English (#14's rule).
        let name = recipe.name, ingredients = recipe.ingredients
        recipe.language = LanguageWords.resolve(declared: photoLanguage, page: nil) {
            LanguageWords.detectionText(name: name, ingredients: ingredients)
        }
        Task { [weak self, repository, recipe] in
            let result = await repository.saveClip(recipe)
            guard let self else { return }
            uiState.saving = false
            switch result {
            case .success(let saved): uiState.savedId = saved.id
            case .notKept: uiState.libraryFull = true
            case .error: uiState.saveFailed = true
            }
        }
    }
}

/// Where line `index` of a box's `old` lines is in its `new` lines, after one edit (#286): the
/// lines before the first that changed, and after the last, are the same lines (moved by as many
/// as were added or taken away). A line inside the change keeps its place in it while the change
/// still has a line there (typed over, or split by a new line), and is gone when it hasn't (the
/// line deleted): nil. Android's `followLine`.
func followLine(_ old: [String], _ new: [String], _ index: Int) -> Int? {
    var start = 0
    while start < old.count, start < new.count, old[start] == new[start] { start += 1 }
    var endOld = old.count
    var endNew = new.count
    while endOld > start, endNew > start, old[endOld - 1] == new[endNew - 1] {
        endOld -= 1
        endNew -= 1
    }
    if index < start { return index }
    if index >= endOld { return index + new.count - old.count }
    return index < endNew ? index : nil
}
