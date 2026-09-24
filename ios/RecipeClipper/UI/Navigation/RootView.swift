import SwiftUI

/// The single NavigationStack. Every destination gets its ViewModel from the container, once
/// per stack entry (ScreenHost), and takes it as a parameter so a screen never builds its own.
struct RootView: View {
    let container: AppContainer
    @Bindable var router: Router
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack(path: $router.path) {
            ScreenHost(container.makeHomeViewModel) { vm in
                HomeScreen(
                    vm: vm,
                    onOpenUrl: { router.push(.importUrl($0)) },
                    onOpenRecipe: { router.push(.recipe(id: $0)) },
                    onOpenHistory: { router.push(.history) },
                    onOpenLists: { router.push(.lists) },
                    onOpenSettings: { router.push(.settings) },
                    onNewRecipe: { router.push(.editRecipe(id: nil)) }
                )
            }
            .navigationDestination(for: Route.self, destination: destination)
        }
        .tint(Palette.accentText)
        .onOpenURL { router.handle($0) }
        // The share extension saves from its own process; catch up on coming back.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { container.refreshAfterExternalChanges() }
        }
        #if DEBUG
        .task { if router.path.isEmpty { router.path = DebugLaunch.initialPath } }
        #endif
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .recipe(let id):
            recipe { container.makeRecipeViewModel(recipeId: id, url: nil) }
        case .cookRecipe(let id):
            recipe { container.makeRecipeViewModel(recipeId: id, url: nil, openInCookMode: true) }
        case .importUrl(let url):
            recipe { container.makeRecipeViewModel(recipeId: nil, url: url) }
        case .history:
            ScreenHost(container.makeHistoryViewModel) { vm in
                HistoryScreen(vm: vm, onOpenRecipe: { router.push(.recipe(id: $0)) })
            }
        case .settings:
            ScreenHost(container.makeSettingsViewModel) { vm in SettingsScreen(vm: vm) }
        case .lists:
            ScreenHost(container.makeListsViewModel) { vm in
                ListsScreen(vm: vm, onOpenList: { router.push(.listDetail(id: $0)) })
            }
        case .editRecipe(let id):
            // Saving replaces the edit screen and, when editing, the recipe screen under it.
            ScreenHost({ container.makeEditRecipeViewModel(recipeId: id) }) { vm in
                EditRecipeScreen(vm: vm, onSaved: { saved in
                    router.replace(last: id == nil ? 1 : 2, with: .recipe(id: saved))
                })
            }
        case .listDetail(let id):
            ScreenHost({ container.makeListDetailViewModel(listId: id) }) { vm in
                ListDetailScreen(vm: vm, onOpenRecipe: { router.push(.recipe(id: $0)) })
            }
        case .clip(let url):
            ScreenHost({ container.makeClipViewModel(url: url) }) { vm in
                ClipScreen(vm: vm, fixtureHTML: container.clipFixtureHTML, onSaved: router.openSavedClip)
            }
        }
    }

    private func recipe(_ make: @escaping () -> RecipeViewModel) -> some View {
        ScreenHost2(makeA: make, makeB: container.makeSaveToListViewModel) { vm, saveVM in
            RecipeScreen(vm: vm, saveVM: saveVM, onEdit: { router.push(.editRecipe(id: $0)) },
                         onClip: { router.push(.clip($0)) })
        }
    }
}
