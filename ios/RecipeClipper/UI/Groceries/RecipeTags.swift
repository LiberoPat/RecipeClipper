import SwiftUI

/// The recipes a grocery line is for (#422; Android's `RecipeTagRow`), as small tags on their own
/// line under its amount and note, so the two never blur: each a rounded paprika wash with a book
/// glyph, "Mushroom omelette · Thu" when it is planned, wrapping. Up to three show; more, the first
/// two and a quiet "+N" (`GroceryRecipeTags.shown`). A tap opens the recipe (`onOpen`);
/// `idPrefix` names them for UI tests ("<prefix>-<recipe id>-<day>"). Used by Groceries and Shop for
/// this week, so they match.
struct RecipeTagRow: View {
    let tags: [RecipeTag]
    let onOpen: (RecipeTag) -> Void
    let idPrefix: String

    var body: some View {
        let (shown, more) = GroceryRecipeTags.shown(tags)
        TagFlow(spacing: 6, lineSpacing: 2) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, tag in
                RecipeTagChip(tag: tag, onOpen: { onOpen(tag) })
                    .accessibilityIdentifier("\(idPrefix)-\(tag.recipeId)-\(tag.day ?? 0)")
            }
            if more > 0 {
                // Not a button: every recipe is on the Recipes screen.
                Text("+\(more)")
                    .textStyle(Typography.labelMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.horizontal, 4)
                    .accessibilityLabel(Strings.groceryTagMore(more))
                    .accessibilityIdentifier("\(idPrefix)-more")
            }
        }
    }
}

/// One tag: a button that reads "For Mushroom omelette, Thursday". The wash is the accent text
/// colour (paprika on the ground, the lighter on-ink paprika in dark), as the Home timer's tag. The
/// touch area is a little taller than the pill, so a thumb finds it without taking much of the row's
/// own tap.
private struct RecipeTagChip: View {
    let tag: RecipeTag
    let onOpen: () -> Void

    var body: some View {
        let visible = tag.day.map { "\(tag.title) · \(PlanDayFormat.shortWeekday($0))" } ?? tag.title
        let spoken = tag.day.map { Strings.groceryTagForDay(tag.title, PlanDayFormat.fullWeekday($0)) }
            ?? Strings.groceryTagFor(tag.title)
        Button(action: onOpen) {
            HStack(spacing: 4) {
                Image(systemName: "book").imageScale(.small).accessibilityHidden(true)
                Text(visible).textStyle(Typography.labelMedium).lineLimit(1)
            }
            .foregroundStyle(Palette.accentText)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Palette.accentText.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
            .padding(.vertical, 3)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(spoken)
    }
}
