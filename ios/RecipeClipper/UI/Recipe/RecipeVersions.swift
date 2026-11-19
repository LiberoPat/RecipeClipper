import SwiftUI

/// "Based on <original>" (#354), quietly under a version's title, in the same muted line as "From a
/// chat": a tap opens the original. Only while the original is still here (deleting it clears the
/// link, so a version never points at nothing).
struct BasedOnLine: View {
    let original: RecipeLink
    let onOpen: (Int64) -> Void

    var body: some View {
        Button { onOpen(original.id) } label: {
            Text(Strings.recipeBasedOn(original.title))
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.accentText)
                .lineLimit(1)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(Strings.openRecipeNamed(original.title))
        .accessibilityIdentifier("recipe.basedOn")
    }
}

/// The versions saved from a recipe (#354), newest first, as a quiet list after its steps: one line
/// each, a tap opens it.
struct VersionsSection: View {
    let versions: [RecipeLink]
    let onOpen: (Int64) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(Strings.recipeVersionsHeading)
                .textStyle(Typography.titleSmall)
                .foregroundStyle(Palette.muted)
                .accessibilityAddTraits(.isHeader)
            ForEach(versions) { version in
                Button { onOpen(version.id) } label: {
                    Text(version.title)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.accentText)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(Strings.openRecipeNamed(version.title))
            }
        }
        .accessibilityIdentifier("recipe.versions")
    }
}
