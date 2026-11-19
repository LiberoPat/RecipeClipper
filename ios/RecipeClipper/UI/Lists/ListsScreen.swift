import SwiftUI

/// Every list as a square cover in a two-column grid (#286), built-ins first. A "+" in the top
/// bar, as on Recipes, makes a new list (#371; it was the grid's last tile, out of reach below
/// many lists), its name field opening just under the title. Renaming and deleting are on the
/// list's own screen, so this one only lists and creates. There is no empty state: the built-in lists are seeded when the
/// database is created.
///
/// The grid is rows of HStacks in a plain VStack, not a LazyVGrid: there are only ever a few
/// lists, and every cover stays in the accessibility tree (a lazy grid drops the ones below
/// the fold, so VoiceOver's rotor and the UI tests couldn't find them).
struct ListsScreen: View {
    let vm: ListsViewModel
    let onOpenList: (Int64) -> Void
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
                VStack(spacing: 18) {
                    ForEach(CoverTile.rows(state.lists, perRow: perRow)) { row in
                        HStack(alignment: .top, spacing: 12) {
                            ForEach(row.tiles) { tile in
                                cell(tile, state: state).frame(maxWidth: .infinity, alignment: .leading)
                            }
                            if row.tiles.count < perRow {
                                // Keeps a lone last tile at half width, in the left column.
                                Color.clear.frame(height: 0).frame(maxWidth: .infinity)
                            }
                        }
                    }
                }
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
    }

    @ViewBuilder private func cell(_ tile: CoverTile, state: ListsUiState) -> some View {
        switch tile {
        case .list(let list):
            ListCoverTile(list: list, photos: state.coverPhotos[list.id] ?? []) { onOpenList(list.id) }
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

    struct Row: Identifiable {
        let tiles: [CoverTile]
        var id: String { tiles[0].id }
    }

    /// The lists in order, `perRow` to a row.
    static func rows(_ lists: [RecipeList], perRow: Int) -> [Row] {
        let tiles = lists.map { CoverTile.list($0) }
        return stride(from: 0, to: tiles.count, by: perRow).map {
            Row(tiles: Array(tiles[$0..<min($0 + perRow, tiles.count)]))
        }
    }
}
