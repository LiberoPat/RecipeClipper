import Observation
import SwiftUI

/// What a recipe row's long-press menu offers (#376, #377). A nil closure leaves its entry out.
struct RecipeRowActions {
    var onAddToList: () -> Void
    var onAddToPlan: (() -> Void)?
    var onShare: () -> Void
    /// Starts selecting with this row ticked (the `multiSelect` flag).
    var onSelect: (() -> Void)?
    /// A list's detail only: leaving a list is not a deletion.
    var onRemoveFromList: (() -> Void)?
    var onDelete: () -> Void
}

extension View {
    /// The long-press menu on a row; no menu while `actions` is nil (the flag off, or selecting).
    func recipeRowMenu(_ actions: RecipeRowActions?) -> some View {
        contextMenu {
            if let actions {
                Button(action: actions.onAddToList) {
                    Label(Strings.addToListAction, systemImage: "bookmark")
                }
                if let onAddToPlan = actions.onAddToPlan {
                    Button(action: onAddToPlan) {
                        Label(Strings.addToPlan, systemImage: "calendar.badge.plus")
                    }
                }
                Button(action: actions.onShare) {
                    Label(Strings.share, systemImage: "square.and.arrow.up")
                }
                if let onSelect = actions.onSelect {
                    Button(action: onSelect) {
                        Label(Strings.select, systemImage: "checkmark.circle")
                    }
                }
                if let onRemove = actions.onRemoveFromList {
                    Button(action: onRemove) {
                        Label(Strings.removeFromList, systemImage: "minus.circle")
                    }
                }
                Button(role: .destructive, action: actions.onDelete) {
                    Label(Strings.delete, systemImage: "trash")
                }
            }
        }
    }

    /// The confirmation before deleting `recipes` (a row's menu, or the selection's): one dialog
    /// for all. The buttons act on the ViewModel's own list, so the dialog closing first can't
    /// empty what Delete reads.
    func recipeDeleteAlert(
        _ recipes: [RecipeSummary], onConfirm: @escaping () -> Void, onCancel: @escaping () -> Void
    ) -> some View {
        alert(
            recipes.count == 1 ? Strings.deleteRecipeTitle(recipes[0].title) : Strings.deleteRecipesTitle(recipes.count),
            isPresented: Binding(get: { !recipes.isEmpty }, set: { _ in })
        ) {
            Button(Strings.delete, role: .destructive, action: onConfirm)
            Button(Strings.cancel, role: .cancel, action: onCancel)
        } message: {
            Text(recipes.count == 1 ? Strings.deleteRowBody : Strings.deleteRowsBody)
        }
    }
}

/// What the row menus have open: the save-to-list sheet, the plan sheet and the share sheet. One
/// per screen; `rowMenuSheets` presents them.
@MainActor
@Observable
final class RowMenuPresenter {
    var saveSheetOpen = false
    var planSheet: AddToPlanViewModel?
    /// The text to share, set once it is rendered.
    var sharing: SharedRecipeText?
    @ObservationIgnored private var planVM: AddToPlanViewModel?

    struct SharedRecipeText: Equatable {
        let title: String
        let text: String
    }

    /// The existing save-to-list sheet, for one recipe or for the selection.
    func addToList(_ recipes: [RecipeSummary], saveVM: SaveToListViewModel) {
        saveVM.setRecipes(recipes.map(\.id), titles: Dictionary(recipes.map { ($0.id, $0.title) }, uniquingKeysWith: { a, _ in a }))
        saveSheetOpen = true
    }

    /// The existing Add to plan sheet, starting from the recipe's own yield.
    func addToPlan(_ recipe: RecipeSummary, makePlanVM: @escaping () -> AddToPlanViewModel, yieldServings: @escaping () async -> Int?) {
        Task {
            let yield = await yieldServings()
            let plan = planVM ?? makePlanVM()
            planVM = plan
            plan.setRecipe(recipe.id, yieldServings: yield)
            planSheet = plan
        }
    }

    /// The recipe as the reading view would send it, in the system share sheet.
    func share(_ text: @escaping () async -> (title: String, text: String)?) {
        Task {
            guard let shared = await text() else { return }
            sharing = SharedRecipeText(title: shared.title, text: shared.text)
        }
    }
}

extension View {
    /// The sheets a row's menu opens (#376): save to list, add to plan and share.
    func rowMenuSheets(_ presenter: RowMenuPresenter, saveVM: SaveToListViewModel?) -> some View {
        modifier(RowMenuSheets(presenter: presenter, saveVM: saveVM))
    }
}

private struct RowMenuSheets: ViewModifier {
    @Bindable var presenter: RowMenuPresenter
    let saveVM: SaveToListViewModel?

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $presenter.saveSheetOpen) {
                if let saveVM {
                    SaveToListSheet(vm: saveVM)
                        .presentationDetents([.medium, .large])
                        .presentationDragIndicator(.visible)
                }
            }
            .sheet(item: $presenter.planSheet) { plan in
                AddToPlanSheet(vm: plan)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            .background(RecipeTextShareAnchor(item: presenter.sharing) { presenter.sharing = nil })
    }
}

/// Presents the system share sheet on a recipe's text when it is set, from this view (a popover's
/// source on iPad), with the recipe's name as the subject as the reading view's share has.
private struct RecipeTextShareAnchor: UIViewRepresentable {
    let item: RowMenuPresenter.SharedRecipeText?
    let onDone: () -> Void

    final class Coordinator {
        var presented: RowMenuPresenter.SharedRecipeText?
    }

    /// The text, with the name as the subject for Mail and the like.
    private final class Source: NSObject, UIActivityItemSource {
        let title: String
        let text: String
        init(_ item: RowMenuPresenter.SharedRecipeText) { title = item.title; text = item.text }
        func activityViewControllerPlaceholderItem(_ controller: UIActivityViewController) -> Any { text }
        func activityViewController(_ controller: UIActivityViewController, itemForActivityType type: UIActivity.ActivityType?) -> Any? { text }
        func activityViewController(_ controller: UIActivityViewController, subjectForActivityType type: UIActivity.ActivityType?) -> String { title }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        guard let item else {
            context.coordinator.presented = nil
            return
        }
        guard context.coordinator.presented != item else { return }
        context.coordinator.presented = item
        let onDone = onDone
        DispatchQueue.main.async {
            guard var presenter = view.window?.rootViewController else { return onDone() }
            while let next = presenter.presentedViewController { presenter = next }
            let controller = UIActivityViewController(activityItems: [Source(item)], applicationActivities: nil)
            controller.popoverPresentationController?.sourceView = view
            controller.popoverPresentationController?.sourceRect = view.bounds
            controller.completionWithItemsHandler = { _, _, _, _ in onDone() }
            presenter.present(controller, animated: true)
        }
    }
}

/// The top bar while selecting (#377): the count, announced as it changes, and Done.
struct RecipesSelectionTitle: View {
    let count: Int

    var body: some View {
        Text(Strings.recipesSelectedCount(count))
            .textStyle(Typography.titleMedium)
            .foregroundStyle(Palette.onBackground)
            .accessibilityAddTraits(.updatesFrequently)
            .accessibilityIdentifier("recipes.selectedCount")
    }
}

/// The action bar under a selection (#377): Add to list, Remove from this list (a list's detail
/// only) and Delete. Each waits for something ticked.
struct RecipesSelectionBar: View {
    let count: Int
    let onAddToList: () -> Void
    var onRemoveFromList: (() -> Void)? = nil
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            barButton(Strings.addToListAction, "bookmark", id: "recipes.selection.addToList", action: onAddToList)
            if let onRemoveFromList {
                barButton(Strings.removeFromList, "minus.circle", id: "recipes.selection.removeFromList", action: onRemoveFromList)
            }
            barButton(Strings.delete, "trash", id: "recipes.selection.delete", tint: Palette.error, action: onDelete)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(Palette.background)
        .overlay(alignment: .top) { Hairline() }
    }

    private func barButton(
        _ title: String, _ icon: String, id: String, tint: Color = Palette.onBackground, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: icon)
                Text(title).textStyle(Typography.labelSmall).multilineTextAlignment(.center)
            }
            .foregroundStyle(count == 0 ? Palette.muted : tint)
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(count == 0)
        .accessibilityIdentifier(id)
    }
}
