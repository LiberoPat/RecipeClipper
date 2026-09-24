import SwiftUI

/// The reading view opens on the recipe: photo, title, times, one servings-and-units row,
/// ingredients. Cooking is one bottom button.
struct ReadingView: View {
    let content: RecipeSuccess
    let state: RecipeUiState
    let vm: RecipeViewModel
    /// The step-number column grows with the numbers in it (titleMedium follows .headline).
    @ScaledMetric(relativeTo: .headline) private var numberColumn: CGFloat = 32
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let recipe = content.recipe
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let image = recipe.image, let url = URL(string: image) {
                    RecipePhoto(url: url, name: recipe.name)
                        .padding(.bottom, 16)
                }
                Text(recipe.name)
                    .textStyle(Typography.headlineSmall)
                    .foregroundStyle(Palette.onBackground)
                    .padding(.bottom, 14)
                Times(prep: recipe.prepTime, cook: recipe.cookTime, total: recipe.totalTime)
                    .padding(.bottom, 16)
                ServesUnitsRow(
                    servings: content.servings,
                    yieldText: recipe.yield,
                    unitSystem: state.unitSystem,
                    onServingsChange: vm.onServingsChange,
                    onUnitSystemChange: vm.onUnitSystemChange
                )
                .padding(.bottom, 20)

                SectionHeading(Strings.headingIngredients).padding(.bottom, 6)
                ForEach(Array(content.ingredients.enumerated()), id: \.offset) { index, ingredient in
                    IngredientRow(
                        text: ingredient,
                        checked: state.checkedIngredients.contains(index),
                        onCheckedChange: { vm.onIngredientChecked(index, $0) }
                    )
                }

                SectionHeading(Strings.headingInstructions)
                    .padding(.top, 24)
                    .padding(.bottom, 6)
                ForEach(Array(content.instructions.enumerated()), id: \.offset) { index, step in
                    // The number column grows with the text up to the largest standard size.
                    // At the accessibility sizes a column wide enough for "12" at ~60pt would
                    // take a third of the screen from the step, so the number goes above it.
                    let stacked = dynamicTypeSize.isAccessibilitySize
                    let layout = stacked
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 0))
                        : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 0))
                    layout {
                        Text("\(index + 1)")
                            .textStyle(Typography.titleMedium)
                            .foregroundStyle(Palette.accentText)
                            .frame(width: stacked ? nil : numberColumn, alignment: .leading)
                        Text(step)
                            .textStyle(Typography.bodyLarge)
                            .foregroundStyle(Palette.onBackground)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, 8)
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !content.instructions.isEmpty {
                VStack(spacing: 0) {
                    Hairline()
                    Button(Strings.startCooking, action: vm.onCookStart)
                        .buttonStyle(PrimaryButtonStyle(minHeight: 52, fillWidth: true))
                        .padding(.horizontal, 20)
                        .readableColumn()
                        .padding(.vertical, 12)
                }
                .background(Palette.background)
            }
        }
    }
}

private struct RecipePhoto: View {
    let url: URL
    let name: String

    var body: some View {
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
            .accessibilityLabel(name)
    }
}

/// Plain labeled numbers. Deliberately not chips: chips imply tappable, these aren't.
private struct Times: View {
    let prep: String?
    let cook: String?
    let total: String?

    var body: some View {
        let entries = [
            prep.map { TimeEntry(label: Strings.labelPrep, value: $0) },
            cook.map { TimeEntry(label: Strings.labelCook, value: $0) },
            total.map { TimeEntry(label: Strings.labelTotal, value: $0) }
        ].compactMap { $0 }
        if !entries.isEmpty {
            // In a row while the three fit; stacked once they don't (the accessibility sizes,
            // or a long "1 hr 15 mins"), rather than wrapping each value mid-number.
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 28) {
                    ForEach(entries, id: \.label) { TimeCell(entry: $0).fixedSize() }
                }
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(entries, id: \.label) { TimeCell(entry: $0) }
                }
            }
        }
    }
}

private struct TimeCell: View {
    let entry: TimeEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(entry.label.uppercased())
                .textStyle(Typography.labelSmall)
                .foregroundStyle(Palette.muted)
            Text(entry.value)
                .textStyle(Typography.bodyLargeBold)
                .foregroundStyle(Palette.onBackground)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct TimeEntry {
    let label: String
    let value: String
}
