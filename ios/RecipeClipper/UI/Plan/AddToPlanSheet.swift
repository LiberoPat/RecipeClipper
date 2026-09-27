import SwiftUI

/// "Add to plan" (#49), from the recipe screen's menu: a day of this week or next, a meal type
/// (Dinner to start) and the servings (the recipe's yield to start), then one button. Closes
/// once the meal is written.
struct AddToPlanSheet: View {
    let vm: AddToPlanViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let state = vm.uiState
        PlanSheetContent(
            title: Strings.addToPlan,
            days: state.days,
            today: state.today,
            selectedDay: state.selectedDay,
            onDaySelected: vm.onDaySelected,
            mealTypes: state.mealTypes,
            selectedMealTypeId: state.selectedMealTypeId,
            onMealTypeSelected: vm.onMealTypeSelected,
            servings: state.servings,
            onServingsChange: vm.onServingsChange,
            confirmLabel: Strings.addToDay(PlanDayFormat.title(state.selectedDay)),
            confirmEnabled: state.canAdd,
            onConfirm: vm.onAdd
        )
        .onChange(of: state.added) { _, added in
            if added { dismiss() }
        }
    }
}
