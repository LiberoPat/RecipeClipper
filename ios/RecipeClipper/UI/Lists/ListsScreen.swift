import SwiftUI

/// Every list as a square cover in a two-column grid (#286), built-ins first, with "New list"
/// as the last tile. Renaming and deleting are on the list's own screen, so this one only
/// lists and creates. There is no empty state: the built-in lists are seeded when the
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
                VStack(spacing: 18) {
                    ForEach(CoverTile.rows(state.lists, newList: !state.creatingList, perRow: perRow)) { row in
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
                // The tile opens into today's inline field, under the grid, full width.
                if state.creatingList {
                    NewListControl(
                        creating: true,
                        name: state.newListName,
                        onStart: vm.onStartCreating,
                        onNameChange: vm.onNewListNameChange,
                        onCancel: vm.onCancelCreating,
                        onCreate: vm.onCreateList
                    )
                    .padding(.top, 18)
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
    }

    @ViewBuilder private func cell(_ tile: CoverTile, state: ListsUiState) -> some View {
        switch tile {
        case .list(let list):
            ListCoverTile(list: list, photos: state.coverPhotos[list.id] ?? []) { onOpenList(list.id) }
        case .newList:
            NewListTile(action: vm.onStartCreating)
        }
    }
}

/// One cell of the grid. Ids are prefixed, so a list's can never collide with the tile's.
private enum CoverTile: Identifiable {
    case list(RecipeList)
    case newList

    var id: String {
        switch self {
        case .list(let list): return "list-\(list.id)"
        case .newList: return "new-list"
        }
    }

    struct Row: Identifiable {
        let tiles: [CoverTile]
        var id: String { tiles[0].id }
    }

    /// The lists in order, then the New list tile (unless its field is open), `perRow` to a row.
    static func rows(_ lists: [RecipeList], newList: Bool, perRow: Int) -> [Row] {
        var tiles = lists.map { CoverTile.list($0) }
        if newList { tiles.append(.newList) }
        return stride(from: 0, to: tiles.count, by: perRow).map {
            Row(tiles: Array(tiles[$0..<min($0 + perRow, tiles.count)]))
        }
    }
}
