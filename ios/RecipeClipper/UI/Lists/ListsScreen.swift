import SwiftUI

/// Every list as a square cover in a two-column grid (#286), in the cook's own order (#381: a
/// press and hold lifts a cover to drag it into place; VoiceOver gets Move earlier and Move
/// later on each cover instead). A "+" in the top bar, as on Recipes, makes a new list (#371; it
/// was the grid's last tile, out of reach below many lists), its name field opening just under
/// the title. Renaming and deleting are on the list's own screen, so this one only lists,
/// creates and reorders. There is no empty state: the built-in lists are seeded when the
/// database is created.
///
/// The grid is `CoverGridLayout`, not a LazyVGrid: there are only ever a few lists, and every
/// cover stays in the accessibility tree (a lazy grid drops the ones below the fold, so
/// VoiceOver's rotor and the UI tests couldn't find them).
struct ListsScreen: View {
    let vm: ListsViewModel
    let onOpenList: (Int64) -> Void
    /// The `reorderLists` flag (#381). Off, the covers can't be moved.
    var reorderEnabled = true
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Two covers to a row; one at the accessibility text sizes, where half the width can't
    /// fit a list's name without breaking it mid-word.
    private var perRow: Int { typeSize.isAccessibilitySize ? 1 : 2 }

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(Strings.listsTitle).padding(.bottom, 14)
                // The "+" opens today's inline field, under the title, full width.
                if state.creatingList {
                    NewListControl(
                        creating: true,
                        name: state.newListName,
                        onStart: vm.onStartCreating,
                        onNameChange: vm.onNewListNameChange,
                        onCancel: vm.onCancelCreating,
                        onCreate: vm.onCreateList
                    )
                    .padding(.bottom, 18)
                }
                CoverGridLayout(columns: perRow) {
                    ForEach(CoverTile.tiles(state.lists)) { tile in
                        cell(tile, state: state)
                    }
                }
                .animation(.snappy, value: state.lists.map(\.id))
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: vm.onStartCreating) { Image(systemName: "plus") }
                    .accessibilityLabel(Strings.newListTile)
                    .accessibilityIdentifier("lists.newList")
            }
        }
        // A drop that lands between two covers still keeps the order.
        .onDrop(of: [.text], delegate: ListDropDelegate(targetId: nil, vm: vm))
        .onDisappear { vm.onDragCancel() }
    }

    @ViewBuilder private func cell(_ tile: CoverTile, state: ListsUiState) -> some View {
        switch tile {
        case .list(let list):
            let index = state.lists.firstIndex { $0.id == list.id } ?? 0
            ListCoverTile(list: list, photos: state.coverPhotos[list.id] ?? []) { onOpenList(list.id) }
                .modifier(ReorderableCover(
                    enabled: reorderEnabled, listId: list.id, held: state.draggingId == list.id, vm: vm
                ))
                .modifier(MoveActions(
                    earlier: reorderEnabled && index > 0 ? { vm.onMoveEarlier(list.id) } : nil,
                    later: reorderEnabled && index < state.lists.count - 1 ? { vm.onMoveLater(list.id) } : nil
                ))
        }
    }
}

/// One cell of the grid, a list's cover. Ids are prefixed.
private enum CoverTile: Identifiable {
    case list(RecipeList)

    var id: String {
        switch self {
        case .list(let list): return "list-\(list.id)"
        }
    }

    /// The lists in order.
    static func tiles(_ lists: [RecipeList]) -> [CoverTile] {
        lists.map { CoverTile.list($0) }
    }
}
