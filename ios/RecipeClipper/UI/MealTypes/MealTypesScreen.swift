import SwiftUI

/// Meal types (#49; Android's MealTypesScreen), from the Week menu: each row's menu renames it,
/// moves it up or down and, for the user's own, deletes it (its meals move to Dinner, and the
/// confirmation says so). "+ New meal type" expands inline, as "+ New list" does.
struct MealTypesScreen: View {
    let vm: MealTypesViewModel

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(Strings.mealTypesTitle).padding(.bottom, 12)
                Hairline()
                ForEach(Array(state.types.enumerated()), id: \.element.id) { index, type in
                    HStack {
                        Text(type.name)
                            .textStyle(Typography.bodyLarge)
                            .foregroundStyle(Palette.onBackground)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Menu {
                            Button(Strings.rename) { vm.onRenameStart(type) }
                            if index > 0 { Button(Strings.moveUp) { vm.onMoveUp(type) } }
                            if index < state.types.count - 1 { Button(Strings.moveDown) { vm.onMoveDown(type) } }
                            if !type.isBuiltIn {
                                Button(Strings.delete, role: .destructive) { vm.onDeleteStart(type) }
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle").frame(minWidth: 44, minHeight: 44)
                        }
                        .accessibilityLabel(Strings.moreOptions)
                        .accessibilityIdentifier("mealTypeMenu-\(type.id)")
                    }
                    .padding(.vertical, 4)
                    Hairline()
                }
                NewMealTypeControl(vm: vm, creating: state.creating, name: state.newName)
                    .padding(.top, 16)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
            .readableColumn()
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .alert(
            Strings.renameMealTypeTitle,
            isPresented: Binding(get: { state.renaming != nil }, set: { if !$0 { vm.onRenameDismissed() } })
        ) {
            TextField(Strings.mealTypeName, text: Binding(get: { vm.uiState.renameText }, set: vm.onRenameTextChange))
            Button(Strings.rename, action: vm.onRenameConfirm)
            Button(Strings.cancel, role: .cancel, action: vm.onRenameDismissed)
        }
        .alert(
            Strings.deleteMealTypeTitle(state.deleting?.name ?? ""),
            isPresented: Binding(get: { state.deleting != nil }, set: { if !$0 { vm.onDeleteDismissed() } })
        ) {
            Button(Strings.delete, role: .destructive, action: vm.onDeleteConfirm)
            Button(Strings.cancel, role: .cancel, action: vm.onDeleteDismissed)
        } message: {
            Text(Strings.deleteMealTypeBody)
        }
    }
}

/// "+ New meal type", expanding inline to a field with Cancel / Create.
private struct NewMealTypeControl: View {
    let vm: MealTypesViewModel
    let creating: Bool
    let name: String

    var body: some View {
        if creating {
            VStack(alignment: .trailing, spacing: 4) {
                OutlinedField(
                    label: Strings.mealTypeName,
                    text: Binding(get: { name }, set: vm.onNewNameChange),
                    onSubmit: vm.onCreate
                )
                HStack(spacing: 4) {
                    Button(Strings.cancel, action: vm.onCancelCreating).buttonStyle(TextActionStyle())
                    Button(Strings.create, action: vm.onCreate)
                        .buttonStyle(TextActionStyle())
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        } else {
            Button(Strings.newMealType, action: vm.onStartCreating)
                .buttonStyle(TextActionStyle())
                .padding(.leading, -12)
        }
    }
}
