import SwiftUI

/// Everything you've opened, newest first. Automatic: nothing here was saved on purpose.
struct RecipesScreen: View {
    let vm: RecipesViewModel
    let onOpenRecipe: (Int64) -> Void

    @State private var now = currentMillis()
    /// A List's rows take insets, not a frame, so the readable column is made from the width.
    @State private var sideInset = ReadableWidth.gutter

    var body: some View {
        let state = vm.uiState
        List {
            Group {
                VStack(alignment: .leading, spacing: 12) {
                    ScreenTitle(Strings.recipesTitle, style: Typography.headlineSmall)
                    SearchField(query: state.query, onQueryChange: vm.onQueryChange)
                }
                .padding(.bottom, 12)
                .listRowSeparator(.hidden)

                if let recipes = state.recipes, recipes.isEmpty {
                    Text(state.query.trimmingCharacters(in: .whitespaces).isEmpty
                         ? Strings.recipesEmpty
                         : Strings.recipesNoResults(state.query))
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.muted)
                        .listRowSeparator(.hidden)
                }

                ForEach(state.recipes ?? []) { recipe in
                    // The hairline above each row is drawn, not a list separator: adjacent
                    // rows share one separator, so hiding a row's bottom edge also hid the
                    // next row's top and no line showed at all.
                    VStack(spacing: 0) {
                        Hairline()
                        RecipeRow(recipe: recipe, now: now) { onOpenRecipe(recipe.id) }
                    }
                        .listRowSeparator(.hidden)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) { vm.onDelete(recipe) } label: {
                                Label(Strings.delete, systemImage: "trash")
                            }
                            .tint(Palette.error)
                        }
                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                            Button(role: .destructive) { vm.onDelete(recipe) } label: {
                                Label(Strings.delete, systemImage: "trash")
                            }
                            .tint(Palette.error)
                        }
                }
            }
            .listRowInsets(EdgeInsets(top: 0, leading: sideInset, bottom: 0, trailing: sideInset))
            .listRowBackground(Palette.background)
        }
        .listStyle(.plain)
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width in
            sideInset = ReadableWidth.inset(in: width)
        }
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .bottom) {
            if let message = Strings.deletedMessage(state.pendingDeletes) {
                Snackbar(message: message, actionLabel: Strings.undo, action: vm.onUndoDelete)
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: state.pendingDeletes.isEmpty)
        // Keyed on the whole pending list: a second swipe restarts the timeout for a snackbar
        // naming the batch. The restart cancels the sleep, so the dismissal below never runs
        // for the earlier capture, which stays for the new snackbar's Undo.
        .task(id: state.pendingDeletes) {
            await SnackbarTimeout.run(pending: state.pendingDeletes, onTimeout: vm.onSnackbarDismissed)
        }
        .onAppear { now = currentMillis() }
    }
}

private struct SearchField: View {
    let query: String
    let onQueryChange: (String) -> Void

    var body: some View {
        HStack(spacing: 8) {
            // The glyphs are capped at the largest non-accessibility size: uncapped, the
            // magnifier alone reached ~60pt at AX5 and took a fifth of the field's width from
            // the query it decorates. The query text itself is not capped.
            Image(systemName: "magnifyingglass").foregroundStyle(Palette.muted)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            TextField(Strings.searchRecipes, text: Binding(get: { query }, set: onQueryChange))
                .textStyle(Typography.bodyLarge)
                .foregroundStyle(Palette.onBackground)
                .autocorrectionDisabled()
                .submitLabel(.search)
            if !query.isEmpty {
                Button { onQueryChange("") } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Palette.muted)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Strings.clearSearch)
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 52)
        .background(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.outline, lineWidth: 1))
    }
}

/// An inverse card with one action, like Material's Snackbar: ink on the ground, light on ink.
struct Snackbar: View {
    let message: String
    /// Nil shows the message alone (a confirmation, with nothing to undo).
    let actionLabel: String?
    var action: () -> Void = {}
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        // At the accessibility sizes Undo goes under the message, which then gets the full
        // width and as many lines as it needs, instead of two truncated lines beside a button.
        let large = dynamicTypeSize.isAccessibilitySize
        let layout = large
            ? AnyLayout(VStackLayout(alignment: .trailing, spacing: 0))
            : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Text(message)
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.inverseOnSurface)
                .lineLimit(large ? nil : 2)
                .padding(.top, large ? 8 : 0)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let actionLabel {
                Button(actionLabel, action: action)
                    .buttonStyle(TextActionStyle(color: Palette.inversePrimary))
                    .fixedSize()
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 8).fill(Palette.inverseSurface))
        .shadow(color: .black.opacity(0.15), radius: 6, y: 2)
    }
}
