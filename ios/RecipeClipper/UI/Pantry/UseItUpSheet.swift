import SwiftUI

/// The "Use it up" sheet (#305; Android's `UseItUpSheet`): for each pantry item expiring soon,
/// the saved recipes that use it, each with one tap to plan it today or on the item's use-by day
/// (tomorrow, the reminder's window). A recipe already planned that day says so instead.
struct UseItUpSheet: View {
    let vm: UseItUpViewModel

    var body: some View {
        let state = vm.uiState
        ScrollView {
            // A plain VStack: few rows, and a lazy stack would need ids unique across both ForEach (#185).
            VStack(alignment: .leading, spacing: 0) {
                SectionHeading(Strings.useItUpTitle)
                Text(Strings.useItUpNote)
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 8)
                ForEach(state.sheet ?? [], id: \.item.id) { group in
                    Text(Strings.useItUpHeading(
                        group.item.name.trimmingCharacters(in: .whitespacesAndNewlines),
                        today: group.item.expiresDay == state.today
                    ))
                    .textStyle(Typography.titleMedium)
                    .foregroundStyle(Palette.accentText)
                    .padding(.top, 16)
                    .padding(.bottom, 4)
                    ForEach(group.recipes) { recipe in
                        row(recipe, item: group.item, state: state)
                    }
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(Palette.background)
        .accessibilityIdentifier("useItUpSheet")
    }

    private func row(_ recipe: SavedRecipe, item: PantryItem, state: UseItUpUiState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(recipe.title).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { buttons(recipe, item: item, state: state) }
                VStack(alignment: .leading, spacing: 8) { buttons(recipe, item: item, state: state) }
            }
        }
        .padding(.vertical, 8)
        .overlay(alignment: .bottom) { Hairline() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("useItUp-\(item.id)-\(recipe.id)")
    }

    @ViewBuilder
    private func buttons(_ recipe: SavedRecipe, item: PantryItem, state: UseItUpUiState) -> some View {
        ForEach(UseItUp.planDays(item, today: state.today), id: \.self) { day in
            let isToday = day == state.today
            let planned = state.planned.contains(PlannedRecipeDay(recipeId: recipe.id, day: day))
            Button(label(isToday: isToday, planned: planned)) { vm.onPlan(recipeId: recipe.id, day: day) }
                .buttonStyle(OutlinedActionStyle())
                .disabled(planned)
                .opacity(planned ? 0.5 : 1)
        }
    }

    private func label(isToday: Bool, planned: Bool) -> String {
        switch (planned, isToday) {
        case (true, true): Strings.useItUpPlannedToday
        case (true, false): Strings.useItUpPlannedTomorrow
        case (false, true): Strings.useItUpPlanToday
        case (false, false): Strings.useItUpPlanTomorrow
        }
    }
}
