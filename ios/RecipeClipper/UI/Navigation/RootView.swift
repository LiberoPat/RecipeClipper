import SwiftUI

/// The single NavigationStack. Every destination gets its ViewModel from the container, once
/// per stack entry (ScreenHost), and takes it as a parameter so a screen never builds its own.
struct RootView: View {
    let container: AppContainer
    @Bindable var router: Router

    var body: some View {
        NavigationStack(path: $router.path) {
            ScreenHost(container.makeHomeViewModel) { vm in
                HomeScreen(
                    vm: vm,
                    onOpenUrl: { router.push(.importUrl($0)) },
                    onOpenRecipe: { router.push(.recipe(id: $0)) },
                    onOpenHistory: { router.push(.history) },
                    onOpenLists: { router.push(.lists) },
                    onOpenSettings: { router.push(.settings) }
                )
            }
            .navigationDestination(for: Route.self, destination: destination)
        }
        .tint(Palette.accentText)
        .onOpenURL { router.handle($0) }
        #if DEBUG
        .task { if router.path.isEmpty { router.path = DebugLaunch.initialPath } }
        #endif
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .recipe(let id):
            recipe { container.makeRecipeViewModel(recipeId: id, url: nil) }
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
            RecipeScreen(vm: vm, saveVM: saveVM, onClip: { router.push(.clip($0)) })
        }
    }
}
