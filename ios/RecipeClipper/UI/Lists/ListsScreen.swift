import SwiftUI

/// Every list with its count, built-ins first. Renaming and deleting are on the list's own
/// screen, so this one only lists and creates. There is no empty state: the built-in lists
/// are seeded when the database is created.
struct ListsScreen: View {
    let vm: ListsViewModel
    let onOpenList: (Int64) -> Void

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(Strings.listsTitle).padding(.bottom, 12)
                Hairline()
                ForEach(state.lists) { list in
                    NavRow(title: list.name, action: { onOpenList(list.id) }) {
                        Text(Strings.listCount(list.recipeCount))
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(Palette.muted)
                    }
                }
                NewListControl(
                    creating: state.creatingList,
                    name: state.newListName,
                    onStart: vm.onStartCreating,
                    onNameChange: vm.onNewListNameChange,
                    onCancel: vm.onCancelCreating,
                    onCreate: vm.onCreateList
                )
                .padding(.top, 16)
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
    }
}
