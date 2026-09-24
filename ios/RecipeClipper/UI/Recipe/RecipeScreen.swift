import SwiftUI

/// The recipe screen: picks between the reading view, cook mode and a loading/error view.
/// Holds a SaveToListViewModel whether or not the sheet is open, because the bookmark icon
/// has to know whether the recipe is in any list before anything is tapped.
struct RecipeScreen: View {
    let vm: RecipeViewModel
    let saveVM: SaveToListViewModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.openURL) private var openURL
    @State private var sheetOpen = false
    @State private var confirmingDelete = false

    var body: some View {
        let state = vm.uiState
        let content = state.content.success
        // The id isn't known until the parse finishes on the import route.
        let recipeId = content?.recipe.id

        Group {
            switch state.content {
            case .success(let success):
                if state.cook.active {
                    CookView(content: success, state: state, vm: vm)
                } else {
                    ReadingView(content: success, state: state, vm: vm)
                }
            case .loading:
                StatusView {
                    ProgressView().tint(Palette.primary).controlSize(.large)
                }
            case .error(let error):
                StatusView {
                    Text(Strings.message(for: error))
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.error)
                    // Every error offers "Try again", no-recipe included: a café or hotel captive
                    // portal serves its login page, which parses as a page with no recipe, and
                    // the same link works once you're through it.
                    // Side by side; stacked when an accessibility text size won't fit them.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) { errorActions(state) }
                        VStack(alignment: .leading, spacing: 8) { errorActions(state) }
                    }
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
        .timerAlerts(state.cook.timers, onAlerted: vm.onTimerAlerted)
        .task(id: recipeId) {
            if let recipeId {
                saveVM.setRecipe(recipeId)
                VisibleRecipe.id = recipeId
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
        .alert(
            Strings.deleteRecipeTitle(content?.recipe.name ?? ""),
            isPresented: $confirmingDelete
        ) {
            Button(Strings.delete, role: .destructive, action: vm.onDelete)
            Button(Strings.cancel, role: .cancel) {}
        } message: {
            Text(Strings.deleteRecipeBody)
        }
    }

    /// "Try again", and beside it "Report this site" only for a page with no recipe (the
    /// ViewModel decides): the one error that means "unsupported" rather than "try again".
    /// Secondary, so it reads as a text action next to the filled button.
    @ViewBuilder
    private func errorActions(_ state: RecipeUiState) -> some View {
        Button(Strings.tryAgain, action: vm.onRetry)
            .buttonStyle(PrimaryButtonStyle())
        if let report = state.reportSiteUrl.flatMap(URL.init(string:)) {
            Button(Strings.reportSite) { openURL(report) }
                .buttonStyle(TextActionStyle(color: Palette.muted))
        }
    }

    /// Bookmark (filled once in any list), share, and an overflow holding Delete. Reading view
    /// only: in cook mode the top bar is Exit + the step counter.
    @ViewBuilder
    private func readingActions(_ content: RecipeSuccess) -> some View {
        let saved = saveVM.uiState.isSaved
        Button { sheetOpen = true } label: {
            Image(systemName: saved ? "bookmark.fill" : "bookmark")
        }
        .accessibilityLabel(saved ? Strings.inAList : Strings.saveToList)
        .accessibilityIdentifier("recipe.bookmark")

        if let text = vm.shareText() {
            ShareLink(item: text, subject: Text(content.recipe.name), preview: SharePreview(content.recipe.name)) {
                Image(systemName: "square.and.arrow.up")
            }
            .accessibilityLabel(Strings.shareRecipe)
        }

        Menu {
            Button(role: .destructive) { confirmingDelete = true } label: {
                Label(Strings.delete, systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel(Strings.moreOptions)
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
