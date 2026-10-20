import SwiftUI
import UniformTypeIdentifiers

/// Home (#286): Continue cooking as a photo card with where the cook stopped, then the three ways
/// in (Paste a link, Scan a recipe, Type a recipe), Recently viewed as a row of photos, and the
/// Recipes / Lists rows with their counts. Sections with nothing in them don't appear, but the
/// Recipes / Lists block always does. Settings is the gear beside the title.
struct HomeScreen: View {
    let vm: HomeViewModel
    let onOpenUrl: (String) -> Void
    let onOpenRecipe: (Int64) -> Void
    /// "Resume cooking" (#286): the recipe, opened in cook mode.
    var onResumeCooking: (Int64) -> Void = { _ in }
    let onOpenRecipes: () -> Void
    let onOpenLists: () -> Void
    let onOpenSettings: () -> Void
    var onNewRecipe: () -> Void = {}
    /// "Scan a recipe" (#226): the pictures taken or picked, in order; nil hides it (flag off).
    var onScan: (([Data]) -> Void)? = nil

    @State private var restoring = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// The gear grows a little with the title beside it, capped: it is chrome.
    @ScaledMetric(relativeTo: .title) private var gearScaled: CGFloat = 20
    private var gearSize: CGFloat { min(gearScaled, 30) }

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
                AddRecipeBlock(onPasteLink: vm.onPasteLink, onScan: onScan, onTypeRecipe: onNewRecipe)
                    .padding(.top, 20)

                if !state.recent.isEmpty {
                    SectionHeading(Strings.sectionRecentlyViewed).padding(.top, 24)
                    RecentlyViewedRow(recipes: state.recent, onOpenRecipe: onOpenRecipe)
                        .padding(.top, 10)
                }

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
                    NavRow(title: Strings.navLists, detail: state.listCount.map { "\($0)" }, action: onOpenLists)
                        .accessibilityIdentifier("home.nav.lists")
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
        // The Recipes screen's "Paste a link" (#286): Go only for what reads as a link.
        .alert(Strings.pasteLink, isPresented: Binding(
            get: { vm.uiState.pastingLink },
            set: { presented in if !presented { vm.onLinkDismissed() } }
        )) {
            TextField(Strings.labelRecipeUrl, text: Binding(get: { vm.uiState.linkInput }, set: vm.onLinkChange))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            Button(Strings.cancel, role: .cancel, action: vm.onLinkDismissed)
            Button(Strings.go) { if let url = vm.onOpenLink() { onOpenUrl(url) } }
                .disabled(!vm.uiState.canOpenLink)
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
