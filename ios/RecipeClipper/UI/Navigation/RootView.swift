import SwiftUI
import UIKit

/// The app's root: four tabs (#47), each with its own NavigationStack, so each keeps its own
/// place; the Recipes stack is the first, and starts on the library with `libraryFirst` (#375), else on Home.
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
            // The first tip's words depend on what the tab opens on (#375).
            .environment(\.libraryFirst, libraryFirst)
            // A designed image for a recipe with no photo (#403), read by every image slot.
            .environment(\.noPhotoImage, container.featureFlags.isOn(.noPhotoImage))
            // A file sent from another Pith (#149) opens its sheet over whatever is on
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
                case .recipes: if libraryFirst { router.showLibrary() } else { router.openInRecipes(.recipes) }
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
                // Text it left (#306) opens in the editor, to check. A UI test's stand-in for the
                // share (`UITestSeeding`) writes to the same place (#334).
                if phase == .active, let reading = container.takePendingText() {
                    router.openInRecipes(.textRecipe(reading))
                }
                // A recipe written inside an article it left (#435) opens in the editor, to check.
                if phase == .active, let articles = container.takePendingArticle() {
                    router.openInRecipes(.article(articles))
                }
                // A week of recipes it left (#352) opens the meal-plan review.
                if phase == .active, let text = container.takePendingPlan() {
                    router.openInRecipes(.mealPlan(text))
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

    /// Library first (#375): the Recipes tab opens the library, with Home's jobs in it; Home is gone.
    private var libraryFirst: Bool { container.featureFlags.libraryFirst }

    private var recipesStack: some View {
        NavigationStack(path: $router.path) {
            Group {
                if libraryFirst { libraryRoot } else { homeRoot }
            }
            .navigationDestination(for: Route.self) { route in
                destination(route, push: router.push, replace: { router.replace(last: $0, with: $1) })
            }
        }
        .tint(Palette.accentText)
    }

    /// The library as the Recipes tab's first screen (#375): the compact card, the + sheet, the gear.
    private var libraryRoot: some View {
        ScreenHost3(
            makeA: { container.makeRecipesViewModel() }, makeB: container.makeSaveToListViewModel,
            makeC: container.makeHomeViewModel
        ) { vm, saveVM, homeVM in
            recipesScreen(
                vm: vm, saveVM: saveVM, push: router.push,
                root: LibraryRoot(
                    vm: homeVM,
                    onOpenSettings: { router.push(.settings) },
                    onResumeCooking: { router.push(.cookRecipe(id: $0)) },
                    // Today's meal: on this stack, so Back returns to the library (Home's went to the Week's).
                    onOpenPlanned: { id, servings, cook in
                        router.push(cook ? .startWeekRecipe(id: id, servings: servings) : .weekRecipe(id: id, servings: servings))
                    },
                    homeTonight: container.featureFlags.isOn(.homeTonight),
                    makeAskAiVM: container.makeAskAiViewModel,
                    onOpenPro: { router.push(.settingsSection(.pro)) }
                )
            )
        }
    }

    private var homeRoot: some View {
        ScreenHost(container.makeHomeViewModel) { vm in
            HomeScreen(
                vm: vm,
                onOpenUrl: { router.push(.importUrl($0)) },
                onOpenPasted: { openPasted($0, push: router.push) },
                onOpenRecipe: { router.push(.recipe(id: $0)) },
                onResumeCooking: { router.push(.cookRecipe(id: $0)) },
                onOpenPlanned: { id, servings, cook in
                    // Today's meal (#402): on the Week's stack, as the Week opens a planned recipe.
                    router.openInWeek(cook ? .startWeekRecipe(id: id, servings: servings) : .weekRecipe(id: id, servings: servings))
                },
                homeTonight: container.featureFlags.isOn(.homeTonight),
                onOpenRecipes: { router.push(.recipes) },
                onOpenLists: { router.push(.lists) },
                onOpenSettings: { router.push(.settings) },
                oneCookbook: container.featureFlags.isOn(.oneCookbook),
                onNewRecipe: { router.push(.editRecipe(id: nil)) },
                onScan: scanHandler(push: router.push),
                makeAskAiVM: container.makeAskAiViewModel,
                onOpenPro: { router.push(.settingsSection(.pro)) }
            )
        }
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
                    makeGroceriesVM: container.makeAddToGroceriesViewModel,
                    shopForWeek: container.featureFlags.isOn(.shopForWeek),
                    // The Pro screen (#413), on this stack, so Back returns to the week.
                    onOpenPro: { router.weekPath.append(.settingsSection(.pro)) }
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

    /// The Groceries tab (#50), with its own stack (#422): a line's recipe tag opens the recipe
    /// here, so Back returns to the list. A pasted list (#149) that goes in the pantry opens the
    /// Pantry tab.
    private var groceriesStack: some View {
        NavigationStack(path: $router.groceriesPath) {
            ScreenHost2(makeA: container.makeGroceriesViewModel, makeB: container.makeReceiveListViewModel) { vm, receiveVM in
                GroceriesScreen(
                    vm: vm, receiveVM: receiveVM, takePendingList: container.takePendingList,
                    onOpenPantry: { router.select(.pantry) },
                    recipeTags: container.featureFlags.isOn(.groceryRecipeTags),
                    onOpenRecipe: { router.groceriesPath.append(.weekRecipe(id: $0, servings: $1)) },
                    selectAll: container.featureFlags.isOn(.pantrySelectAll),
                    makeSendFileVM: container.makeSendFileViewModel
                )
            }
            .navigationDestination(for: Route.self) { route in
                destination(route, push: { router.groceriesPath.append($0) }, replace: { count, route in
                    router.groceriesPath.removeLast(min(count, router.groceriesPath.count))
                    router.groceriesPath.append(route)
                })
            }
        }
        .tint(Palette.accentText)
    }

    /// The Pantry tab (#51): the pantry alone.
    private var pantryStack: some View {
        NavigationStack {
            ScreenHost2(makeA: container.makePantryViewModel, makeB: container.makeUseItUpViewModel) { vm, useItUpVM in
                PantryScreen(
                    vm: vm, makeSendFileVM: container.makeSendFileViewModel, useItUpVM: useItUpVM,
                    useItUpPending: router.useItUpPending, takeUseItUp: router.takeUseItUp
                )
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
            // Recipes written inside the article (#435) likewise: the editor or the chooser, so Back
            // never lands on the import to open them again.
            recipe(
                push: push, onClipBlocked: { replace(1, .clip($0, blocked: true)) },
                onHumanCheck: { replace(1, .clip($0, check: true)) },
                onArticle: { replace(1, .article($0)) }
            ) {
                container.makeRecipeViewModel(recipeId: nil, url: url)
            }
        case .weekRecipe(let id, let servings):
            recipe(push: push) { container.makeRecipeViewModel(recipeId: id, url: nil, plannedServings: servings) }
        case .startWeekRecipe(let id, let servings):
            recipe(push: push) {
                container.makeRecipeViewModel(recipeId: id, url: nil, plannedServings: servings, startInCookMode: true)
            }
        case .mealTypes:
            ScreenHost(container.makeMealTypesViewModel) { vm in MealTypesScreen(vm: vm) }
        case .whatINeed(let weekStart):
            ScreenHost({ container.makeWhatINeedViewModel(weekStart: weekStart) }) { vm in
                WhatINeedScreen(
                    vm: vm, shopForWeek: container.featureFlags.isOn(.shopForWeek),
                    recipeTags: container.featureFlags.isOn(.groceryRecipeTags),
                    onOpenRecipe: { push(.weekRecipe(id: $0, servings: $1)) },
                    onOpenPro: { push(.settingsSection(.pro)) }
                )
            }
        case .recipes:
            ScreenHost2(makeA: { container.makeRecipesViewModel() }, makeB: container.makeSaveToListViewModel) { vm, saveVM in
                recipesScreen(vm: vm, saveVM: saveVM, push: push, root: nil)
            }
        case .recipesView(let view):
            // One cookbook (#345): an automatic view, with the save-to-list bookmark on its rows.
            ScreenHost2(makeA: { container.makeRecipesViewModel(view: view) }, makeB: container.makeSaveToListViewModel) { vm, saveVM in
                RecipesScreen(
                    vm: vm,
                    onOpenRecipe: { push(.recipe(id: $0)) },
                    oneCookbook: true,
                    saveVM: saveVM,
                    quickFilters: container.featureFlags.isOn(.recipeFilters),
                    gridEnabled: container.featureFlags.isOn(.recipesGrid),
                    rowActions: container.featureFlags.isOn(.rowActions),
                    multiSelect: container.featureFlags.isOn(.multiSelect),
                    makePlanVM: container.makeAddToPlanViewModel
                )
            }
        case .settings:
            ScreenHost(container.makeSettingsViewModel) { vm in
                SettingsScreen(
                    vm: vm,
                    onOpenSection: { push(.settingsSection($0)) },
                    onOpenDeveloperSettings: { push(.developerSettings) }
                )
            }
        case .settingsSection(.pro):
            // Pro (#413): its page is the Pro screen itself, the one every locked extra opens, on any stack.
            ScreenHost(container.makeProViewModel) { vm in ProScreen(vm: vm) }
        case .settingsSection(let section):
            // Each section's page (#286); Back returns to the index.
            ScreenHost(container.makeSettingsViewModel) { vm in
                SettingsSectionScreen(
                    section: section, vm: vm, onShowTips: container.tooltips.onReplay,
                    onOpenPro: { push(.settingsSection(.pro)) }
                )
            }
        case .developerSettings:
            ScreenHost(container.makeDeveloperSettingsViewModel) { vm in DeveloperSettingsScreen(vm: vm) }
        case .lists:
            ScreenHost(container.makeListsViewModel) { vm in
                ListsScreen(
                    vm: vm, onOpenList: { push(.listDetail(id: $0)) },
                    reorderEnabled: container.featureFlags.isOn(.reorderLists)
                )
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
            ScreenHost2(makeA: { container.makeListDetailViewModel(listId: id) }, makeB: container.makeSaveToListViewModel) { vm, saveVM in
                ListDetailScreen(
                    vm: vm, onOpenRecipe: { push(.recipe(id: $0)) }, makePrintVM: container.makePrintViewModel,
                    rowActions: container.featureFlags.isOn(.rowActions),
                    multiSelect: container.featureFlags.isOn(.multiSelect),
                    saveVM: saveVM, makePlanVM: container.makeAddToPlanViewModel
                )
            }
        case .clip(let url, let blocked, let check):
            ScreenHost({ container.makeClipViewModel(url: url, blocked: blocked, check: check) }) { vm in
                ClipScreen(
                    vm: vm, fixtureHTML: container.clipFixtureHTML, onSaved: router.openSavedClip,
                    // Past Cloudflare's check, recipes written inside the article (#435), in the clip's place.
                    onArticle: { replace(1, .article($0)) }
                )
            }
        case .photoRecipe(let post):
            // The checked recipe replaces the editor and the post's error screen, as a saved clip does.
            ScreenHost({ container.makeEditRecipeViewModel(photo: post) }) { vm in
                EditRecipeScreen(vm: vm, onSaved: router.openSavedClip)
            }
            .toolbar(.hidden, for: .tabBar)
        case .articleRecipe(let article):
            // As a Reddit photo's (#198): the checked recipe replaces the editor and the page's
            // error screen.
            ScreenHost({ container.makeEditRecipeViewModel(article: article) }) { vm in
                EditRecipeScreen(vm: vm, onSaved: router.openSavedClip)
            }
            .toolbar(.hidden, for: .tabBar)
        case .articleChoice(let articles):
            ScreenHost({ container.makeArticleChoiceViewModel(articles: articles) }) { vm in
                ArticleChoiceScreen(vm: vm, onCheck: { push(.articleRecipe($0)) }, onOpenSaved: { push(.recipe(id: $0)) })
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
        case .textRecipe(let reading):
            // Text shared in (#306): the saved recipe replaces the review, as a scan's does.
            ScreenHost({ container.makeEditRecipeViewModel(shared: reading) }) { vm in
                EditRecipeScreen(vm: vm, onSaved: { saved in replace(1, .recipe(id: saved)) })
            }
            .toolbar(.hidden, for: .tabBar)
        case .mealPlan(let text):
            // A week of recipes shared in (#352): saving replaces the review with Week when any
            // recipe was planned, else goes back to where it came from.
            ScreenHost({ container.makeMealPlanReviewViewModel(text: text) }) { vm in
                MealPlanReviewScreen(vm: vm, onFinished: { planned in
                    router.path.removeAll { $0 == route }
                    if planned { router.select(.week) }
                }, onOpenPro: { push(.settingsSection(.pro)) })
            }
            .toolbar(.hidden, for: .tabBar)
        case .cutOff:
            CutOffScreen()
                .toolbar(.hidden, for: .tabBar)
        }
    }

    /// The Recipes screen. The + menu: "Type a recipe" is Home's "+ New recipe"; "Paste a link" is Home's
    /// field. With `root` (#375) it is the Recipes tab's first screen, and the + opens the Add a recipe sheet.
    private func recipesScreen(
        vm: RecipesViewModel, saveVM: SaveToListViewModel, push: @escaping (Route) -> Void, root: LibraryRoot?
    ) -> some View {
        RecipesScreen(
            vm: vm,
            onOpenRecipe: { push(.recipe(id: $0)) },
            onNewRecipe: { push(.editRecipe(id: nil)) },
            onOpenUrl: { push(.importUrl($0)) },
            onOpenPasted: { openPasted($0, push: push) },
            onScan: scanHandler(push: push),
            oneCookbook: container.featureFlags.isOn(.oneCookbook),
            saveVM: saveVM,
            onOpenView: { push(.recipesView($0)) },
            onOpenLists: { push(.lists) },
            quickFilters: container.featureFlags.isOn(.recipeFilters),
            gridEnabled: container.featureFlags.isOn(.recipesGrid),
            rowActions: container.featureFlags.isOn(.rowActions),
            multiSelect: container.featureFlags.isOn(.multiSelect),
            makePlanVM: container.makeAddToPlanViewModel,
            libraryRoot: root
        )
    }

    /// Where pasted text leads (#372, `PastedTextRouter`): the routes a share opens, from inside
    /// the app. A link imports, text opens the editor or the meal-plan review, a list is offered
    /// on Groceries.
    private func openPasted(_ target: PasteTarget, push: (Route) -> Void) {
        switch target {
        case .link(let url): push(.importUrl(url))
        case .review(let reading): push(.textRecipe(reading))
        case .plan(let text): push(.mealPlan(text))
        case .list(let text):
            container.pendingListText = text
            router.select(.groceries)
        case .cutOff: push(.cutOff)
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
        onArticle: @escaping ([ArticleRecipe]) -> Void = { _ in },
        _ make: @escaping () -> RecipeViewModel
    ) -> some View {
        let container = container
        return ScreenHost2(makeA: make, makeB: container.makeSaveToListViewModel) { vm, saveVM in
            RecipeScreen(
                vm: vm, saveVM: saveVM, onEdit: { push(.editRecipe(id: $0)) },
                onOpenRecipe: { push(.recipe(id: $0)) },
                makePlanVM: container.makeAddToPlanViewModel, makeGroceriesVM: container.makeAddToGroceriesViewModel,
                onClip: { push(.clip($0)) },
                onClipBlocked: onClipBlocked,
                onHumanCheck: onHumanCheck,
                onArticle: onArticle,
                onReadPhoto: { push(.photoRecipe($0)) },
                photoTextEnabled: container.featureFlags.isOn(.photoText),
                makePhotosVM: container.makeCookedPhotosViewModel,
                makeSendFileVM: container.makeSendFileViewModel,
                makeUseUpVM: container.makePantryUseUpViewModel,
                pantrySelectAll: container.featureFlags.isOn(.pantrySelectAll),
                makePrintVM: container.makePrintViewModel,
                makeAskAiVM: container.makeAskAiViewModel
            )
        }
        // The reading view and cook mode are full screen, so a recipe still opens on the recipe.
        .toolbar(.hidden, for: .tabBar)
    }
}
