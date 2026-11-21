import SwiftUI

/// Several recipes written inside one article (#435; Android's `ArticleChoiceScreen`): the page's
/// photo and headline, then one plain row per recipe with its name and counts. A row opens the
/// editor on that recipe; one saved already says so and opens as itself.
struct ArticleChoiceScreen: View {
    let vm: ArticleChoiceViewModel
    let onCheck: (ArticleRecipe) -> Void
    let onOpenSaved: (Int64) -> Void

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PostPreview(title: state.headline, imageUrl: state.image)
                Text(Strings.articleChooseNote)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 20)
                Text(Strings.articleChooseTitle)
                    .textStyle(Typography.titleSmall)
                    .foregroundStyle(Palette.primary)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.bottom, 4)
                ForEach(state.rows, id: \.index) { row in
                    Button {
                        if let id = row.savedId { onOpenSaved(id) } else { onCheck(vm.articles[row.index]) }
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.name)
                                .textStyle(Typography.titleMedium)
                                .foregroundStyle(Palette.onBackground)
                                .multilineTextAlignment(.leading)
                            Text(row.savedId != nil ? Strings.articleChooseSaved : Strings.articleChooseCounts(row.ingredients, row.steps))
                                .textStyle(Typography.bodyMedium)
                                .foregroundStyle(Palette.muted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("article.row.\(row.index)")
                    Hairline()
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        // Back from the editor or a saved recipe: which are saved by now.
        .onAppear(perform: vm.onShown)
    }
}
