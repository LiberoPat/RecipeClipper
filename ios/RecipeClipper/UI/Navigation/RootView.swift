import SwiftUI
import UIKit

/// The app's root. With the `mealPlan` flag off (#87, until the meal plan ships) it is the single
/// Recipes NavigationStack, exactly as before the tab shell. On, that same stack is the first of
/// four tabs, each with its own NavigationStack, so each keeps its own place.
/// Every destination gets its ViewModel from the container, once per stack entry (ScreenHost),
/// and takes it as a parameter so a screen never builds its own.
struct RootView: View {
    let container: AppContainer
    @Bindable var router: Router
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        tabs
            // The tooltips (#190): every screen's TooltipHost reads them from here.
            .environment(container.tooltips)
            // A file sent from another Recipe Clipper (#149) opens its sheet over whatever is on
            // screen; a `recipeclipper://` link imports as before.
            .onOpenURL { url in
                if url.isFileURL, let receive = container.receiveFileViewModel {
                    receive.open(url)
                } else {
                    router.handle(url)
                }
            }
            .modifier(ReceiveFileSheet(vm: container.receiveFileViewModel) { added in
                switch added {
                case .groceries: router.select(.groceries)
                case .pantry: router.select(.pantry)
                case .recipes: router.openInRecipes(.recipes)
                }
            })
            // A new user's sample recipe (#151, #190), in the UI's language (English if the sample
            // isn't written in it).
            .task { await container.firstRunTour.onLaunch(language: Bundle.main.preferredLocalizations.first) }
            // A post the share extension couldn't read (#213) opens in "Clip it yourself".
            .onChange(of: scenePhase, initial: true) { _, phase in
                if phase == .active, let url = container.takePendingClip() {
                    router.openInRecipes(.clip(url, blocked: true))
                }
                // Images the share extension left (#226) open the scan's review.
                if phase == .active, let pages = container.takePendingScan() {
                    router.openInRecipes(.scanRecipe(pages))
                }
            }
            // The share extension saves from its own process; catch up on coming back.
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { container.refreshAfterExternalChanges() }
                // The automatic backup copy (#150): leaving the app is when a changed library is
                // copied, with a little background time asked for so the write can finish.
                if phase == .background, let autoBackup = container.autoBackup { backUp(autoBackup) }
            }
            #if DEBUG
            .task {
                if router.path.isEmpty { router.path = DebugLaunch.initialPath }
                // A UI test's stand-in for a file opened from Messages (#149).
                if let file = UITestSeeding.receivedFileURL() {
                    container.receiveFileViewModel?.open(file)
                }
                // A UI test's stand-in for a scan (#226): local pictures, read by the real reader.
                if let pages = UITestSeeding.scanPages() {
                    router.openInRecipes(.scanRecipe(pages))
                }
            }
            #endif
    }

    /// A platform effect, so here: the background time the copy runs in.
    private func backUp(_ autoBackup: AutoBackup) {
        let app = UIApplication.shared
        var task = UIBackgroundTaskIdentifier.invalid
        task = app.beginBackgroundTask(withName: "auto-backup") {
            app.endBackgroundTask(task)
            task = .invalid
        }
        Task {
            await autoBackup.run()
            if task != .invalid { app.endBackgroundTask(task) }
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
                    onNewRecipe: { router.push(.editRecipe(id: nil)) },
                    onScan: scanHandler(push: router.push)
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

    /// The Groceries tab (#50): the list alone, for now. A pasted list (#149) that goes in the
    /// pantry opens the Pantry tab.
    private var groceriesStack: some View {
        NavigationStack {
            ScreenHost2(makeA: container.makeGroceriesViewModel, makeB: container.makeReceiveListViewModel) { vm, receiveVM in
                GroceriesScreen(
                    vm: vm, receiveVM: receiveVM, onOpenPantry: { router.select(.pantry) },
                    makeSendFileVM: container.makeSendFileViewModel
                )
            }
        }
        .tint(Palette.accentText)
    }

    /// The Pantry tab (#51): the pantry alone.
    private var pantryStack: some View {
        NavigationStack {
            ScreenHost(container.makePantryViewModel) { vm in
                PantryScreen(vm: vm, makeSendFileVM: container.makeSendFileViewModel)
            }
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
            // Reddit's block (#213) replaces the import with the clip, so Back never lands on an
            // error screen.
            // Cloudflare's check (#220) likewise, for the cook to pass it.
            recipe(
                push: push, onClipBlocked: { replace(1, .clip($0, blocked: true)) },
                onHumanCheck: { replace(1, .clip($0, check: true)) }
            ) {
                container.makeRecipeViewModel(recipeId: nil, url: url)
            }
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
                    onOpenUrl: { push(.importUrl($0)) },
                    onScan: scanHandler(push: push)
                )
            }
        case .settings:
            ScreenHost(container.makeSettingsViewModel) { vm in
                SettingsScreen(
                    vm: vm,
                    onOpenDeveloperSettings: { push(.developerSettings) },
                    onShowTips: container.tooltips.onReplay
                )
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
        case .clip(let url, let blocked, let check):
            ScreenHost({ container.makeClipViewModel(url: url, blocked: blocked, check: check) }) { vm in
                ClipScreen(vm: vm, fixtureHTML: container.clipFixtureHTML, onSaved: router.openSavedClip)
            }
        case .photoRecipe(let post):
            // The checked recipe replaces the editor and the post's error screen, as a saved clip does.
            ScreenHost({ container.makeEditRecipeViewModel(photo: post) }) { vm in
                EditRecipeScreen(vm: vm, onSaved: router.openSavedClip)
            }
            .toolbar(.hidden, for: .tabBar)
        case .scanRecipe(let pages):
            // "Scan a recipe" (#226): the saved recipe replaces the review, as a typed-in one does,
            // and the pages go.
            ScreenHost({ container.makeEditRecipeViewModel(scanPages: pages) }) { vm in
                EditRecipeScreen(vm: vm, onSaved: { saved in
                    container.scanPages.clear()
                    replace(1, .recipe(id: saved))
                })
            }
            .toolbar(.hidden, for: .tabBar)
        }
    }

    /// "Scan a recipe" (#226), behind the photoText flag: the pictures taken or picked become
    /// the scan's pages on disk, then its review opens. Nil hides the entries.
    private func scanHandler(push: @escaping (Route) -> Void) -> (([Data]) -> Void)? {
        guard container.featureFlags.isOn(.photoText) else { return nil }
        let pages = container.scanPages
        return { pictures in
            Task {
                let staged = await Task.detached(priority: .userInitiated) { pages.stage(pictures) }.value
                if !staged.isEmpty { push(.scanRecipe(staged)) }
            }
        }
    }

    private func recipe(
        push: @escaping (Route) -> Void, onClipBlocked: @escaping (String) -> Void = { _ in },
        onHumanCheck: @escaping (String) -> Void = { _ in },
        _ make: @escaping () -> RecipeViewModel
    ) -> some View {
        let container = container
        return ScreenHost2(makeA: make, makeB: container.makeSaveToListViewModel) { vm, saveVM in
            RecipeScreen(
                vm: vm, saveVM: saveVM, onEdit: { push(.editRecipe(id: $0)) },
                makePlanVM: container.makeAddToPlanViewModel, makeGroceriesVM: container.makeAddToGroceriesViewModel,
                onClip: { push(.clip($0)) },
                onClipBlocked: onClipBlocked,
                onHumanCheck: onHumanCheck,
                onReadPhoto: { push(.photoRecipe($0)) },
                photoTextEnabled: container.featureFlags.isOn(.photoText),
                makePhotosVM: container.makeCookedPhotosViewModel,
                makeSendFileVM: container.makeSendFileViewModel,
                makeUseUpVM: container.makePantryUseUpViewModel
            )
        }
        // The reading view and cook mode are full screen, so a recipe still opens on the recipe.
        .toolbar(.hidden, for: .tabBar)
    }
}
