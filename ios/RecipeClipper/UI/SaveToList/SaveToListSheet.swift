import SwiftUI

/// Spotify's add-to-playlist model: checkboxes, because a recipe belongs in as many lists as
/// you like, and every tick writes immediately. No Save or Cancel — the sheet is dismissed,
/// not submitted. "+ New list" expands inline rather than stacking a dialog on the sheet.
/// The ViewModel is passed in because the recipe screen's bookmark icon shares it.
struct SaveToListSheet: View {
    let vm: SaveToListViewModel

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeading(Strings.saveToListTitle).padding(.bottom, 8)
                ForEach(state.lists) { list in
                    ListCheckRow(list: list) { vm.onListToggled(listId: list.id, inList: $0) }
                }
                NewListControl(
                    creating: state.creatingList,
                    name: state.newListName,
                    onStart: vm.onStartCreating,
                    onNameChange: vm.onNewListNameChange,
                    onCancel: vm.onCancelCreating,
                    onCreate: vm.onCreateList
                )
                .padding(.top, 8)
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 24)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .presentationBackground(Palette.background)
    }
}

/// The whole row toggles; the checkbox only draws the state.
private struct ListCheckRow: View {
    let list: RecipeList
    let onToggle: (Bool) -> Void

    var body: some View {
        Button { onToggle(!list.containsRecipe) } label: {
            HStack(spacing: 12) {
                CheckboxGlyph(checked: list.containsRecipe)
                VStack(alignment: .leading, spacing: 0) {
                    Text(list.name).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                    Text(Strings.listCount(list.recipeCount))
                        .textStyle(Typography.bodySmall)
                        .foregroundStyle(Palette.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(list.containsRecipe ? [.isSelected] : [])
    }
}
