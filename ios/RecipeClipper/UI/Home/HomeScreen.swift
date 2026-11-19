import SwiftUI
import UniformTypeIdentifiers

/// Home (#286): Continue cooking as a photo card with where the cook stopped, then the ways in
/// (Paste a link, Scan a recipe, Type a recipe, and Ask an AI, #351), and the Recipes / Lists rows
/// with their counts (no Lists row with `oneCookbook`). No Recently viewed shelf (#371): Recipes' default sort is that. Sections with nothing in them don't appear, but the
/// Recipes / Lists block always does. Settings is the gear beside the title.
struct HomeScreen: View {
    let vm: HomeViewModel
    let onOpenUrl: (String) -> Void
    /// Pasted text that isn't a link (#372): the editor, the meal-plan review, Groceries' list sheet.
    var onOpenPasted: (PasteTarget) -> Void = { _ in }
    let onOpenRecipe: (Int64) -> Void
    /// "Resume cooking" (#286): the recipe, opened in cook mode.
    var onResumeCooking: (Int64) -> Void = { _ in }
    let onOpenRecipes: () -> Void
    let onOpenLists: () -> Void
    let onOpenSettings: () -> Void
    /// One cookbook (#345): Recipes lists the lists and makes new ones, so no Lists row here (#371).
    var oneCookbook = false
    var onNewRecipe: () -> Void = {}
    /// "Scan a recipe" (#226): the pictures taken or picked, in order; nil hides it (flag off).
    var onScan: (([Data]) -> Void)? = nil
    /// Makes "Ask an AI"'s sheet (#351); nil (the askAi flag off, or tests) leaves the tile out.
    /// Made on first use and kept for the screen's life.
    var makeAskAiVM: (() -> AskAiViewModel)? = nil

    @State private var restoring = false
    @State private var askAiVM: AskAiViewModel?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// The gear grows a little with the title beside it, capped: it is chrome.
    @ScaledMetric(relativeTo: .title) private var gearScaled: CGFloat = 20
    private var gearSize: CGFloat { min(gearScaled, 30) }

    /// A link imports as always; any other pasted text goes where the same text shared would (#372).
    private func openPasted(_ target: PasteTarget) {
        if case .link(let url) = target { onOpenUrl(url) } else { onOpenPasted(target) }
    }

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Top-aligned at the accessibility sizes, where the title wraps, so the gear
                // stays by its first line rather than floating between two.
                HStack(alignment: dynamicTypeSize.isAccessibilitySize ? .top : .center) {
                    ScreenTitle(Strings.homeTitle)
                    // Settings' only entry point today. It could open from elsewhere too:
                    // RecipeViewModel follows AppPreferences.settings, so a recipe left
                    // underneath Settings keeps up with a change (#24). Whether the recipe
                    // screen offers it is a product call, not a technical constraint.
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape.fill")
                            .font(.system(size: gearSize))
                            .foregroundStyle(Palette.muted)
                            .frame(width: max(44, gearSize + 16), height: max(44, gearSize + 16))
                            .contentShape(Rectangle())
                    }
                    .offset(x: 10) // optical edge, past the icon's own padding
                    .accessibilityLabel(Strings.navSettings)
                }

                // Continue cooking (#286): a photo card with where the cook stopped.
                if let latest = state.continueCooking {
                    ResumeCard(
                        recipe: latest, resume: state.resume,
                        onResume: { onResumeCooking(latest.id) },
                        onOpen: { onOpenRecipe(latest.id) }
                    )
                    .padding(.top, 16)
                }

                // The three ways in (#286): Paste a link, Scan a recipe (the photoText flag,
                // #226) and Type a recipe (#29), as equal tiles.
                AddRecipeBlock(
                    onPasteLink: vm.onPasteLink, onScan: onScan, onTypeRecipe: onNewRecipe,
                    onAskAi: makeAskAiVM.map { make in
                        {
                            askAiVM = askAiVM ?? make()
                            vm.onAskAi()
                        }
                    }
                )
                    .padding(.top, 20)

                if state.loaded && state.continueCooking == nil {
                    Text(Strings.homeEmptyHint)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.muted)
                        .padding(.top, 28)
                }

                // A fresh install with an empty library (#150): bring a backup back in.
                if state.showsRestore && vm.canRestore {
                    Button(Strings.homeRestoreBackup) { restoring = true }
                        .buttonStyle(TextActionStyle())
                        .disabled(state.restore == .importing)
                        .padding(.top, 8)
                        .accessibilityIdentifier("home.restore")
                    if let status = backupStatusText(state.restore) {
                        Text(status.text)
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(status.isError ? Palette.error : Palette.onBackground)
                            .padding(.top, 8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("home.restoreStatus")
                    }
                }

                // Both entries are unconditional: a fixed block is easier to aim at than one
                // that changes shape with what is in the database.
                VStack(spacing: 0) {
                    Hairline()
                    NavRow(title: Strings.recipesTitle, detail: state.recipeCount.map { "\($0)" }, action: onOpenRecipes)
                        .accessibilityIdentifier("home.nav.recipes")
                    if !oneCookbook {
                        NavRow(title: Strings.navLists, detail: state.listCount.map { "\($0)" }, action: onOpenLists)
                            .accessibilityIdentifier("home.nav.lists")
                    }
                }
                .padding(.top, 20)
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .screenBackground()
        // The tooltips (#190); none while the file picker is up.
        .tooltipHost(.home, blocked: restoring)
        .toolbar(.hidden, for: .navigationBar)
        // The Recipes screen's "Paste a link" (#286), which takes any recipe text (#372): a link
        // imports, anything else goes where a share of it would.
        .sheet(isPresented: Binding(
            get: { vm.uiState.pastingLink },
            set: { presented in if !presented { vm.onLinkDismissed() } }
        )) {
            PasteLinkSheet(
                text: Binding(get: { vm.uiState.linkInput }, set: vm.onLinkChange),
                canOpen: vm.uiState.canOpenLink, noRecipe: vm.uiState.linkNoRecipe,
                onOpen: { if let target = vm.onOpenLink() { openPasted(target) } },
                onCancel: vm.onLinkDismissed
            )
        }
        // Ask an AI (#351): a prompt to copy or share; the sheet starts over each time.
        .sheet(isPresented: Binding(
            get: { vm.uiState.askAiOpen },
            set: { open in
                if !open {
                    askAiVM?.onReset()
                    vm.onAskAiDismissed()
                }
            }
        )) {
            if let askAiVM {
                AskAiSheet(vm: askAiVM, onOpenPasted: { target in
                    // The sheet closes, then the answer opens where a share of it would.
                    askAiVM.onReset()
                    vm.onAskAiDismissed()
                    openPasted(target)
                })
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
        // The same picker as Settings' Import.
        .fileImporter(isPresented: $restoring, allowedContentTypes: [.json, .plainText, .zip]) { result in
            switch result {
            case .success(let url): vm.onRestorePicked(url)
            case .failure: vm.onRestorePickFailed()
            }
        }
    }
}
