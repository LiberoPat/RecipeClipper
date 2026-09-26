import SwiftUI
import UniformTypeIdentifiers

/// Home: the link field (kept so the app can be tried without the share sheet), then whatever
/// there is to pick up again. Sections with nothing in them don't appear, but the Recipes /
/// Lists block always does. Settings is the gear beside the title.
struct HomeScreen: View {
    let vm: HomeViewModel
    let onOpenUrl: (String) -> Void
    let onOpenRecipe: (Int64) -> Void
    let onOpenRecipes: () -> Void
    let onOpenLists: () -> Void
    let onOpenSettings: () -> Void
    var onNewRecipe: () -> Void = {}

    @State private var now = currentMillis()
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
                Text(Strings.homeSubtitle)
                    .textStyle(Typography.bodyLarge)
                    .foregroundStyle(Palette.muted)
                    .padding(.top, 6)

                // Side by side normally; at the accessibility sizes the field gets the whole
                // width and Go sits under it, rather than the URL showing a few characters.
                let urlLayout = dynamicTypeSize.isAccessibilitySize
                    ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
                    : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
                urlLayout {
                    OutlinedField(
                        label: Strings.labelRecipeUrl,
                        text: Binding(get: { vm.uiState.urlInput }, set: vm.onUrlChange),
                        isError: state.urlError,
                        errorText: Strings.errorInvalidUrl,
                        keyboard: .URL,
                        onSubmit: go
                    )
                    Button(Strings.go, action: go)
                        .buttonStyle(PrimaryButtonStyle(minHeight: 52, fillWidth: dynamicTypeSize.isAccessibilitySize))
                }
                .padding(.top, 20)

                // Typing a recipe in by hand (#29): a small way in, not a section.
                Button(Strings.newRecipe, action: onNewRecipe)
                    .buttonStyle(TextActionStyle())
                    .padding(.top, 8)
                    .accessibilityIdentifier("home.newRecipe")

                if let latest = state.continueCooking {
                    SectionHeading(Strings.sectionContinueCooking).padding(.top, 24)
                    RecipeRow(recipe: latest, now: now) { onOpenRecipe(latest.id) }
                }

                if !state.recent.isEmpty {
                    SectionHeading(Strings.sectionRecentlyViewed).padding(.top, 24)
                    ForEach(state.recent) { recipe in
                        RecipeRow(recipe: recipe, now: now) { onOpenRecipe(recipe.id) }
                    }
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
                    NavRow(title: Strings.recipesTitle, action: onOpenRecipes)
                        .accessibilityIdentifier("home.nav.recipes")
                    NavRow(title: Strings.navLists, action: onOpenLists)
                        .accessibilityIdentifier("home.nav.lists")
                }
                .padding(.top, 20)
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { now = currentMillis() }
        // The same picker as Settings' Import.
        .fileImporter(isPresented: $restoring, allowedContentTypes: [.json, .plainText, .zip]) { result in
            switch result {
            case .success(let url): vm.onRestorePicked(url)
            case .failure: vm.onRestorePickFailed()
            }
        }
    }

    private func go() {
        if let url = vm.onGo() { onOpenUrl(url) }
    }
}
