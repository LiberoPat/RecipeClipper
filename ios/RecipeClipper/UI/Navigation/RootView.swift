import SwiftUI

/// The app's root. With `FeatureFlags.mealPlanTabs` off (until #49 ships) it is the single
/// Recipes NavigationStack, exactly as before the tab shell. On, that same stack is the first of
/// four tabs, each with its own NavigationStack, so each keeps its own place.
/// Every destination gets its ViewModel from the container, once per stack entry (ScreenHost),
/// and takes it as a parameter so a screen never builds its own.
struct RootView: View {
    let container: AppContainer
    @Bindable var router: Router
    var tabsEnabled: Bool = FeatureFlags.mealPlanTabs
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        root
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
    private var root: some View {
        if tabsEnabled {
            tabs
        } else {
            recipesStack
        }
    }

    private var recipesStack: some View {
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
    }

    /// Recipes · Week · Groceries · Pantry, the owner's order (#47). The selection goes through
    /// the router, so a share can switch to Recipes and choosing Recipes again returns to Home.
    private var tabs: some View {
        let _ = TabBarStyle.apply
        return TabView(selection: Binding(get: { router.selectedTab }, set: { router.select($0) })) {
            recipesStack
                .tabItem { Label(Strings.tabRecipes, systemImage: "book.closed") }
                .tag(AppTab.recipes)
            placeholder(Strings.tabWeek, Strings.weekPlaceholder)
                .tabItem { Label(Strings.tabWeek, systemImage: "calendar") }
                .tag(AppTab.week)
            placeholder(Strings.tabGroceries, Strings.groceriesPlaceholder)
                .tabItem { Label(Strings.tabGroceries, systemImage: "basket") }
                .tag(AppTab.groceries)
            placeholder(Strings.tabPantry, Strings.pantryPlaceholder)
                .tabItem { Label(Strings.tabPantry, systemImage: "cabinet") }
                .tag(AppTab.pantry)
        }
        .tint(Palette.accentText)
    }

    private func placeholder(_ title: String, _ description: String) -> some View {
        NavigationStack {
            ComingSoonScreen(title: title, description: description)
        }
        .tint(Palette.accentText)
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
            // Full screen, like the recipe and cook mode, so editing isn't a tab of its own.
            .toolbar(.hidden, for: .tabBar)
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
        // The reading view and cook mode are full screen, so a recipe still opens on the recipe.
        // A no-op while the tab bar is off.
        .toolbar(.hidden, for: .tabBar)
    }
}
