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
    /// Lines the recogniser was unsure of, as they were put in the boxes: "check this".
    var uncertain: [String] = []

    init(isNew: Bool, photo: PhotoPost? = nil, scan: Bool = false) {
        self.isNew = isNew
        self.photo = photo
        self.scan = scan
        loading = !isNew
    }
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
        uiState.uncertain = []
        // A post's first picture is its photo; a scan's pages are never kept (#226).
        uiState.draft = RecipeDraft(name: post.title, image: scanPages != nil ? "" : post.imageUrls.first ?? "")
        readTask = Task { [weak self, photoReader] in
            let result = await photoReader.read(post.imageUrls)
            guard let self, !Task.isCancelled else { return }
            uiState.reading = false
            switch result {
            case .failed:
                uiState.photoOutcome = .failed
            case .read(let lines):
                let reading = PhotoTextSorter.sort(lines)
                photoLanguage = reading.language
                uiState.photoOutcome = reading.sorted ? .read : .notSorted
                uiState.uncertain = reading.uncertain
                uiState.draft.yield = reading.yield ?? ""
                uiState.draft.prepTime = reading.prepTime ?? ""
                uiState.draft.cookTime = reading.cookTime ?? ""
                uiState.draft.totalTime = reading.totalTime ?? ""
                uiState.draft.ingredientsText = reading.ingredients.joined(separator: "\n")
                uiState.draft.instructionsText = reading.instructions.joined(separator: "\n")
            }
        }
    }

    func onDraftChange(_ draft: RecipeDraft) {
        uiState.draft = draft
        uiState.saveFailed = false
        uiState.unlockNotice = nil
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
