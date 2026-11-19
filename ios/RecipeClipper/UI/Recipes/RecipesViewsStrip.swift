import SwiftUI

/// One cookbook (#345, V4): under search, a strip of the automatic views (Not in a list, Cooked,
/// Typed by you, From a chat), each a small card with its count, then a fixed "Lists" header (#371:
/// "All lists" opens the Lists screen, "+" makes a new list) above a strip of the lists. A view opens
/// as its own screen (Back goes to Recipes); a list opens its own screen. The views never ask the
/// cook to file anything: they are questions about what is already stored. The header never scrolls
/// sideways, so making a list is always one tap. Android's `RecipesViewsStrip`.
struct RecipesViewsStrip: View {
    let counts: RecipeCounts?
    let lists: [RecipeList]
    let onOpenView: (RecipeFilter) -> Void
    let onOpenList: (Int64) -> Void
    let onOpenLists: () -> Void
    let onNewList: () -> Void

    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    private var width: CGFloat { min(104 * scale, 170) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 8) {
                    ForEach(RecipeFilter.views, id: \.self) { view in
                        card(
                            systemImage: Self.icon(view), name: RecipeFilterPills.name(view), count: counts?.of(view),
                            id: "recipes.view.\(view.rawValue)"
                        ) { onOpenView(view) }
                    }
                }
                .padding(.top, 12)
                .padding(.bottom, 4)
            }
            .accessibilityIdentifier("recipes.viewsStrip")

            listsHeader
                .padding(.top, 8)

            if !lists.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 8) {
                        ForEach(lists) { list in
                            card(
                                systemImage: list.isFavorites ? "heart" : "list.bullet", name: list.name,
                                count: list.recipeCount, id: "recipes.list.\(list.id)"
                            ) { onOpenList(list.id) }
                        }
                    }
                    .padding(.bottom, 12)
                }
                .accessibilityIdentifier("recipes.listsStrip")
            }
        }
    }

    /// The fixed "Lists" header (#371): title, "All lists" (the cover grid) and "+" (a new list).
    private var listsHeader: some View {
        HStack(spacing: 4) {
            Text(Strings.navLists)
                .textStyle(Typography.titleMedium)
                .foregroundStyle(Palette.onBackground)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(Strings.listsAll, action: onOpenLists)
                .buttonStyle(TextActionStyle())
                .accessibilityIdentifier("recipes.allLists")
            Button(action: onNewList) {
                Image(systemName: "plus")
                    .foregroundStyle(Palette.accentText)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(Strings.newListTile)
            .accessibilityIdentifier("recipes.newList")
        }
    }

    private func card(
        systemImage: String, name: String, count: Int?, id: String, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: systemImage)
                    .foregroundStyle(Palette.accentText)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                Text(name)
                    .textStyle(Typography.labelLarge)
                    .foregroundStyle(Palette.onBackground)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text(count.map(String.init) ?? "")
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.muted)
            }
            .padding(10)
            .frame(width: width, alignment: .leading)
            // The same height whether a name takes one line or two, so the strip reads as a row.
            .frame(minHeight: 112 * scale, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 14).fill(Palette.hairline.opacity(0.5)))
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(count.map { "\(name), \($0)" } ?? name)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier(id)
    }

    static func icon(_ view: RecipeFilter) -> String {
        switch view {
        case .cooked: "checkmark"
        case .typed: "pencil"
        case .chat: "bubble.left"
        default: "bookmark"
        }
    }
}

/// The one line under a view's title (V4), saying what it holds.
struct ViewNote: View {
    let view: RecipeFilter

    var body: some View {
        Text(text)
            .textStyle(Typography.bodyMedium)
            .foregroundStyle(Palette.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("recipes.viewNote")
    }

    private var text: String {
        switch view {
        case .notInAList: Strings.recipesViewNotInListNote
        case .cooked: Strings.recipesViewCookedNote
        case .typed: Strings.recipesViewTypedNote
        case .chat: Strings.recipesViewChatNote
        default: ""
        }
    }
}
