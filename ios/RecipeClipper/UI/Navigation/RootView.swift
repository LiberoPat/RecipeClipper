import SwiftUI

/// The app's root. With the `mealPlan` flag off (#87, until the meal plan ships) it is the single
/// Recipes NavigationStack, exactly as before the tab shell. On, that same stack is the first of
/// four tabs, each with its own NavigationStack, so each keeps its own place.
/// Every destination gets its ViewModel from the container, once per stack entry (ScreenHost),
/// and takes it as a parameter so a screen never builds its own.
struct RootView: View {
    let container: AppContainer
    @Bindable var router: Router
    /// The `mealPlan` flag, read through the container's observable flags, so turning it on or
    /// off in Developer settings swaps the root at once.
    private var tabsEnabled: Bool { container.featureFlags.isOn(.mealPlan) }
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
                    onOpenRecipes: { router.push(.recipes) },
                    onOpenLists: { router.push(.lists) },
                    onOpenSettings: { router.push(.settings) },
                    onNewRecipe: { router.push(.editRecipe(id: nil)) }
                )
            }
            .navigationDestination(for: Route.self) { route in
                destination(route, push: router.push, replace: { router.replace(last: $0, with: $1) })
            }
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
            weekStack
                .tabItem { Label(Strings.tabWeek, systemImage: "calendar") }
                .tag(AppTab.week)
            groceriesStack
                .tabItem { Label(Strings.tabGroceries, systemImage: "basket") }
                .tag(AppTab.groceries)
            pantryStack
                .tabItem { Label(Strings.tabPantry, systemImage: "cabinet") }
                .tag(AppTab.pantry)
        }
        .tint(Palette.accentText)
    }

    /// The Week tab (#49), with its own stack: a planned recipe opens here, so Back returns to
    /// the week.
    private var weekStack: some View {
        NavigationStack(path: $router.weekPath) {
            ScreenHost(container.makeWeekViewModel) { vm in
                WeekScreen(
                    vm: vm,
                    onOpenRecipe: { router.weekPath.append(.weekRecipe(id: $0, servings: $1)) },
                    onOpenMealTypes: { router.weekPath.append(.mealTypes) },
                    onOpenWhatINeed: { router.weekPath.append(.whatINeed(weekStart: $0)) },
                    makeGroceriesVM: container.makeAddToGroceriesViewModel
                )
            }
            .navigationDestination(for: Route.self) { route in
                destination(route, push: { router.weekPath.append($0) }, replace: { count, route in
                    router.weekPath.removeLast(min(count, router.weekPath.count))
                    router.weekPath.append(route)
                })
            }
        }
        .tint(Palette.accentText)
    }

    /// The Groceries tab (#50): the list alone, for now.
    private var groceriesStack: some View {
        NavigationStack {
            ScreenHost(container.makeGroceriesViewModel) { vm in GroceriesScreen(vm: vm) }
        }
        .tint(Palette.accentText)
    }

    /// The Pantry tab (#51): the pantry alone.
    private var pantryStack: some View {
        NavigationStack {
            ScreenHost(container.makePantryViewModel) { vm in PantryScreen(vm: vm) }
        }
        .tint(Palette.accentText)
    }

    /// Every destination above a tab's first screen. `push` and `replace` act on the stack the
    /// route was opened in (Recipes, or the Week's own, #49), so Back stays in that tab.
    @ViewBuilder
    private func destination(
        _ route: Route,
        push: @escaping (Route) -> Void,
        replace: @escaping (Int, Route) -> Void
    ) -> some View {
        switch route {
        case .recipe(let id):
            recipe(push: push) { container.makeRecipeViewModel(recipeId: id, url: nil) }
        case .cookRecipe(let id):
            recipe(push: push) { container.makeRecipeViewModel(recipeId: id, url: nil, openInCookMode: true) }
        case .importUrl(let url):
            recipe(push: push) { container.makeRecipeViewModel(recipeId: nil, url: url) }
        case .weekRecipe(let id, let servings):
            recipe(push: push) { container.makeRecipeViewModel(recipeId: id, url: nil, plannedServings: servings) }
        case .mealTypes:
            ScreenHost(container.makeMealTypesViewModel) { vm in MealTypesScreen(vm: vm) }
        case .whatINeed(let weekStart):
            ScreenHost({ container.makeWhatINeedViewModel(weekStart: weekStart) }) { vm in WhatINeedScreen(vm: vm) }
        case .recipes:
            ScreenHost(container.makeRecipesViewModel) { vm in
                // The + menu: "Type a recipe" is Home's "+ New recipe"; "Paste a link" is Home's field.
                RecipesScreen(
                    vm: vm,
                    onOpenRecipe: { push(.recipe(id: $0)) },
                    onNewRecipe: { push(.editRecipe(id: nil)) },
                    onOpenUrl: { push(.importUrl($0)) }
                )
            }
        case .settings:
            ScreenHost(container.makeSettingsViewModel) { vm in
                SettingsScreen(vm: vm, onOpenDeveloperSettings: { push(.developerSettings) })
            }
        case .developerSettings:
            ScreenHost(container.makeDeveloperSettingsViewModel) { vm in DeveloperSettingsScreen(vm: vm) }
        case .lists:
            ScreenHost(container.makeListsViewModel) { vm in
                ListsScreen(vm: vm, onOpenList: { push(.listDetail(id: $0)) })
            }
        case .editRecipe(let id):
            // Saving replaces the edit screen and, when editing, the recipe screen under it.
            ScreenHost({ container.makeEditRecipeViewModel(recipeId: id) }) { vm in
                EditRecipeScreen(vm: vm, onSaved: { saved in
                    replace(id == nil ? 1 : 2, .recipe(id: saved))
                })
            }
            // Full screen, like the recipe and cook mode, so editing isn't a tab of its own.
            .toolbar(.hidden, for: .tabBar)
        case .listDetail(let id):
            ScreenHost({ container.makeListDetailViewModel(listId: id) }) { vm in
                ListDetailScreen(vm: vm, onOpenRecipe: { push(.recipe(id: $0)) })
            }
        case .clip(let url):
            ScreenHost({ container.makeClipViewModel(url: url) }) { vm in
                ClipScreen(vm: vm, fixtureHTML: container.clipFixtureHTML, onSaved: router.openSavedClip)
            }
        }
    }

    private func recipe(push: @escaping (Route) -> Void, _ make: @escaping () -> RecipeViewModel) -> some View {
        // "Add to plan" (#49) only behind the tab flag, like the Week tab itself.
        let container = container
        let makePlanVM: (() -> AddToPlanViewModel)? = tabsEnabled ? { container.makeAddToPlanViewModel() } : nil
        let makeGroceriesVM: (() -> AddToGroceriesViewModel)? =
            tabsEnabled ? { container.makeAddToGroceriesViewModel() } : nil
        return ScreenHost2(makeA: make, makeB: container.makeSaveToListViewModel) { vm, saveVM in
            RecipeScreen(
                vm: vm, saveVM: saveVM, onEdit: { push(.editRecipe(id: $0)) },
                makePlanVM: makePlanVM, makeGroceriesVM: makeGroceriesVM, onClip: { push(.clip($0)) },
                amountsInStepsEnabled: container.featureFlags.isOn(.amountsInSteps),
                makePhotosVM: container.makeCookedPhotosViewModel
            )
        }
        // The reading view and cook mode are full screen, so a recipe still opens on the recipe.
        // A no-op while the tab bar is off.
        .toolbar(.hidden, for: .tabBar)
    }
}
