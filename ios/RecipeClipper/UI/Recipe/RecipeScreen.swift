import SwiftUI

/// The recipe screen: picks between the reading view, cook mode and a loading/error view.
/// Holds a SaveToListViewModel whether or not the sheet is open, because the bookmark icon
/// has to know whether the recipe is in any list before anything is tapped.
struct RecipeScreen: View {
    let vm: RecipeViewModel
    let saveVM: SaveToListViewModel
    var onEdit: (Int64) -> Void = { _ in }
    /// Makes the "Add to plan" sheet's ViewModel (#49); nil hides the menu item, as while the
    /// tab flag is off. Made on first use and kept for the screen's life.
    var makePlanVM: (() -> AddToPlanViewModel)? = nil
    /// Makes the "Add to groceries" sheet's ViewModel (#50); nil hides the menu item.
    var makeGroceriesVM: (() -> AddToGroceriesViewModel)? = nil
    /// Opens "Clip it yourself" on the shared link (#37).
    var onClip: (String) -> Void = { _ in }
    /// The `amountsInSteps` flag (#101): amounts inside steps show only behind it.
    var amountsInStepsEnabled = false
    /// Makes "Your cooks" (#116); nil (the `cookedPhotos` flag off) leaves it out.
    var makePhotosVM: (() -> CookedPhotosViewModel)? = nil
    /// Makes "Send as file" (#149): the recipe as a small file for someone else's app; nil
    /// leaves it out. Made on first use and kept for the screen's life.
    var makeSendFileVM: (() -> SendFileViewModel)? = nil
    /// Makes the pantry's use-up sheet (#147), opened when cook mode is finished with
    /// ingredients ticked; nil (the tab flag off, so no pantry) leaves it out.
    var makeUseUpVM: (() -> PantryUseUpViewModel)? = nil

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.openURL) private var openURL
    @State private var sheetOpen = false
    @State private var planVM: AddToPlanViewModel?
    /// The plan sheet's ViewModel while the sheet is up (`sheet(item:)`, so the sheet is
    /// never built without it).
    @State private var planSheet: AddToPlanViewModel?
    @State private var groceriesVM: AddToGroceriesViewModel?
    @State private var groceriesSheet: AddToGroceriesViewModel?
    @State private var confirmingDelete = false
    @State private var photosVM: CookedPhotosViewModel?
    @State private var confirmingUpdate = false
    @State private var sendFileVM: SendFileViewModel?
    @State private var useUpVM: PantryUseUpViewModel?

    var body: some View {
        let state = vm.uiState
        let content = state.content.success
        let clipped = content?.recipe.origin == .clipped
        // The id isn't known until the parse finishes on the import route, and a recipe shown
        // but not kept (#107) has none.
        let recipeId = state.notKept ? nil : content?.recipe.id

        Group {
            switch state.content {
            case .success(let success):
                let shown = amountsInStepsEnabled ? success : success.withoutStepAmounts
                if state.cook.active {
                    CookView(content: shown, state: state, vm: vm)
                } else {
                    ReadingView(content: shown, state: state, vm: vm, photos: photosVM)
                }
            case .loading:
                StatusView {
                    ProgressView().tint(Palette.primary).controlSize(.large)
                }
            case .error(let error):
                StatusView {
                    if case .noTranscription(let title, let imageUrl) = error {
                        PostPreview(title: title, imageUrl: imageUrl)
                    }
                    Text(Strings.message(for: error))
                        .textStyle(Typography.bodyLarge)
                        // No transcription is an outcome, not a failure: muted, not red.
                        .foregroundStyle(error.isNoTranscription ? Palette.muted : Palette.error)
                    // Every error offers "Try again", no-recipe included: a café or hotel captive
                    // portal serves its login page, which parses as a page with no recipe, and
                    // the same link works once you're through it.
                    errorActions(state)
                        .padding(.top, 16)
                }
            }
        }
        .screenBackground()
        // Cook mode follows the system theme like every other screen unless the user has
        // asked for it to stay dark.
        .environment(\.colorScheme, state.forceDark ? .dark : systemScheme)
        .preferredColorScheme(state.forceDark ? .dark : nil)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(state.cooking ? .hidden : .visible, for: .navigationBar)
        // In cook mode "Exit" is the way out, as Android's BackHandler makes it.
        .navigationBarBackButtonHidden(state.cooking)
        .toolbar {
            if let content, !state.cook.active {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    readingActions(content)
                }
            }
        }
        // A full free library (#107): shown, not kept. Up until unlocked, above the content.
        .safeAreaInset(edge: .bottom) {
            if state.notKept && !state.cooking {
                Snackbar(message: Strings.recipeNotKept, actionLabel: Strings.unlock, action: vm.onUnlock)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                    .readableColumn()
            }
        }
        .alert(
            state.unlockNotice.map(Strings.unlockNotice) ?? "",
            isPresented: Binding(get: { vm.uiState.unlockNotice != nil }, set: { if !$0 { vm.onUnlockNoticeShown() } })
        ) {}
        .timerAlerts(state.cook.timers, onAlerted: vm.onTimerAlerted)
        // "Send as file" (#149): the share sheet on the written file, or a line saying it failed.
        .modifier(SendFileEffect(vm: sendFileVM))
        .task(id: recipeId) {
            if let recipeId {
                saveVM.setRecipe(recipeId)
                VisibleRecipe.id = recipeId
                if photosVM == nil { photosVM = makePhotosVM?() }
                photosVM?.setRecipe(recipeId)
            }
            #if DEBUG
            if recipeId != nil, DebugLaunch.autoCook { vm.onCookStart() }
            #endif
        }
        // While this recipe is on screen its timers beep here instead of showing a banner.
        .onAppear { if let recipeId { VisibleRecipe.id = recipeId } }
        .onDisappear { if let recipeId { VisibleRecipe.clear(recipeId) } }
        // The recipe is gone the moment the delete lands; leave the screen.
        .onChange(of: state.deleted) { _, deleted in
            if deleted { dismiss() }
        }
        .sheet(isPresented: $sheetOpen) {
            SaveToListSheet(vm: saveVM)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(item: $planSheet) { plan in
            AddToPlanSheet(vm: plan)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(item: $groceriesSheet) { groceries in
            AddToGroceriesSheet(vm: groceries)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .alert(
            Strings.deleteRecipeTitle(content?.recipe.name ?? ""),
            isPresented: $confirmingDelete
        ) {
            Button(Strings.delete, role: .destructive, action: vm.onDelete)
            Button(Strings.cancel, role: .cancel) {}
        } message: {
            // The user's photos go with the recipe (#116), and the dialog says so.
            Text(Strings.deleteRecipeBody(photos: photosVM?.uiState.photos.count ?? 0))
        }
        .fullScreenCover(item: Binding(
            get: { photosVM?.uiState.open }, set: { if $0 == nil { photosVM?.onClose() } }
        )) { photo in
            if let photosVM { CookedPhotoViewer(vm: photosVM, photo: photo, recipeName: content?.recipe.name ?? "") }
        }
        .overlay(alignment: .bottom) {
            if let photosVM, photosVM.uiState.deleted != nil {
                Snackbar(message: Strings.cookedPhotoDeleted, actionLabel: Strings.undo, action: photosVM.onUndoDelete)
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 80)
            }
        }
        .task(id: photosVM?.uiState.deleted?.id) {
            guard let photosVM, let deleted = photosVM.uiState.deleted else { return }
            await SnackbarTimeout.run(pending: [deleted.uid], onTimeout: photosVM.onDeleteSettled)
        }
        // Cook mode just finished with ingredients ticked (#147): they go to the pantry's use-up sheet.
        .onChange(of: state.cookFinished) { _, finished in
            guard let finished else { return }
            if useUpVM == nil { useUpVM = makeUseUpVM?() }
            useUpVM?.onCookFinished(language: finished.language, lines: finished.lines)
            vm.onCookFinishedHandled()
        }
        .sheet(isPresented: Binding(
            get: { useUpVM?.uiState.sheet != nil }, set: { if !$0 { useUpVM?.onDismissed() } }
        )) {
            if let useUpVM, let sheet = useUpVM.uiState.sheet {
                PantryUseUpSheet(sheet: sheet, vm: useUpVM)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
        .overlay(alignment: .bottom) {
            if let useUpVM, useUpVM.uiState.updated != nil {
                Snackbar(message: Strings.pantryUsedUp, actionLabel: Strings.undo, action: useUpVM.onUndo)
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 80)
            }
        }
        .task(id: useUpVM?.uiState.updated) {
            guard let useUpVM, let updated = useUpVM.uiState.updated else { return }
            await SnackbarTimeout.run(pending: ["\(updated)"], onTimeout: useUpVM.onUpdatedDismissed)
        }
        // A clip (#37) says what it loses in its own words: the parts picked from the page.
        .alert(clipped ? Strings.updateFromSourceClipTitle : Strings.updateFromSourceTitle, isPresented: $confirmingUpdate) {
            Button(Strings.update, role: .destructive, action: vm.onUpdateFromSource)
            Button(Strings.cancel, role: .cancel) {}
        } message: {
            Text(clipped ? Strings.updateFromSourceClipBody : Strings.updateFromSourceBody)
        }
        // "Update from source" failed: the recipe on screen is unchanged; say why, once.
        .alert(
            state.updateError.map { Strings.updateFromSourceFailed(Strings.message(for: $0)) } ?? "",
            isPresented: Binding(get: { vm.uiState.updateError != nil }, set: { if !$0 { vm.onUpdateErrorShown() } })
        ) {
            // No actions: the system supplies its own, localized OK.
        }
    }

    /// "Try again" on every error. For a page with no recipe data (the ViewModel decides) it is
    /// outlined, and under a hairline "Clip it yourself" (#37) is the one filled button, with
    /// "Report this site" (#30) as the quiet option below it.
    @ViewBuilder
    private func errorActions(_ state: RecipeUiState) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if let clipUrl = state.clipUrl {
                Button(Strings.tryAgain, action: vm.onRetry)
                    .buttonStyle(OutlinedActionStyle())
                Hairline().padding(.top, 20).padding(.bottom, 16)
                Text(Strings.clipOffer)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 12)
                Button(Strings.clipItYourself) { onClip(clipUrl) }
                    .buttonStyle(PrimaryButtonStyle())
            } else {
                Button(Strings.tryAgain, action: vm.onRetry)
                    .buttonStyle(PrimaryButtonStyle())
            }
            if let report = state.reportSiteUrl.flatMap(URL.init(string:)) {
                Button(Strings.reportSite) { openURL(report) }
                    .buttonStyle(TextActionStyle(color: Palette.muted))
                    .padding(.top, 8)
            }
        }
    }

    /// Bookmark (filled once in any list), share, and an overflow holding Delete. Reading view
    /// only: in cook mode the top bar is Exit + the step counter.
    @ViewBuilder
    private func readingActions(_ content: RecipeSuccess) -> some View {
        let saved = saveVM.uiState.isSaved
        // Lists and the overflow's actions need a saved recipe: not one shown but not kept (#107).
        let kept = !vm.uiState.notKept
        if kept {
            Button { sheetOpen = true } label: {
                Image(systemName: saved ? "bookmark.fill" : "bookmark")
            }
            .accessibilityLabel(saved ? Strings.inAList : Strings.saveToList)
            .accessibilityIdentifier("recipe.bookmark")
        }

        if let text = vm.shareText(labels: Strings.shareTextLabels) {
            ShareLink(item: text, subject: Text(content.recipe.name), preview: SharePreview(content.recipe.name)) {
                Image(systemName: "square.and.arrow.up")
            }
            .accessibilityLabel(Strings.shareRecipe)
        }

        if vm.uiState.updatingFromSource {
            ProgressView().tint(Palette.primary)
        }

        if kept { Menu {
            if let makePlanVM {
                Button {
                    let plan = planVM ?? makePlanVM()
                    planVM = plan
                    plan.setRecipe(content.recipe.id, yieldServings: content.servings?.base)
                    planSheet = plan
                } label: {
                    Label(Strings.addToPlan, systemImage: "calendar.badge.plus")
                }
            }
            if let makeGroceriesVM {
                Button {
                    // The lines exactly as the reading view shows them: scaled and converted.
                    let groceries = groceriesVM ?? makeGroceriesVM()
                    groceriesVM = groceries
                    groceries.setRecipe(
                        content.recipe.id, title: content.recipe.name, language: content.words?.language,
                        rendered: content.ingredients
                    )
                    groceriesSheet = groceries
                } label: {
                    Label(Strings.addToGroceries, systemImage: "basket")
                }
            }
            // The share icon stays one tap for text; the file is the second way to send it.
            if let makeSendFileVM {
                Button {
                    let send = sendFileVM ?? makeSendFileVM()
                    sendFileVM = send
                    send.sendRecipe(content.recipe.id, title: content.recipe.name)
                } label: {
                    Label(Strings.sendFile, systemImage: "doc")
                }
                .accessibilityIdentifier("recipe.sendFile")
            }
            Button { onEdit(content.recipe.id) } label: {
                Label(Strings.edit, systemImage: "pencil")
            }
            if content.recipe.canUpdateFromSource && !vm.uiState.updatingFromSource {
                Button { confirmingUpdate = true } label: {
                    Label(Strings.updateFromSource, systemImage: "arrow.clockwise")
                }
            }
            Button(role: .destructive) { confirmingDelete = true } label: {
                Label(Strings.delete, systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel(Strings.moreOptions) }
    }
}

/// A Reddit post with no recipe as text: its title and photo above the note, so the user sees
/// what they shared (the recipe may well be legible in the photo itself). Not the ReadingView:
/// there is nothing to scale, tick or cook.
private struct PostPreview: View {
    let title: String
    let imageUrl: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let imageUrl, let url = URL(string: imageUrl) {
                Rectangle()
                    .fill(Palette.hairline)
                    .frame(height: 200)
                    .overlay {
                        CachedAsyncImage(url: url) { image in
                            image.resizable().scaledToFill()
                        } placeholder: {
                            Color.clear
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .accessibilityLabel(title)
                    .padding(.bottom, 16)
            }
            Text(title)
                .textStyle(Typography.headlineSmall)
                .foregroundStyle(Palette.onBackground)
                .padding(.bottom, 12)
        }
    }
}

private extension ParseError {
    var isNoTranscription: Bool {
        if case .noTranscription = self { return true }
        return false
    }
}

/// Loading and errors: one message, nothing to read yet.
private struct StatusView<Body: View>: View {
    @ViewBuilder let body_: () -> Body
    init(@ViewBuilder _ body: @escaping () -> Body) { body_ = body }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            body_()
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .readableColumn()
        .padding(.top, 24)
    }
}
