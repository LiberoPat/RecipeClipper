import SwiftUI

/// The Week tab (#49; Android's WeekScreen): ‹ week › over one scroll of days that opens with
/// today at the top, each day with its meals and a "+ Add" (#232). The weeks are seven-day blocks
/// from today; the label names the block at the top, the arrows snap to the block before or
/// after, and "Today", away from this week, scrolls back. Tapping a recipe opens it at the
/// planned servings; long-pressing a meal offers Move and Remove (Remove can be undone).
struct WeekScreen: View {
    let vm: WeekViewModel
    let onOpenRecipe: (_ recipeId: Int64, _ servings: Int?) -> Void
    let onOpenMealTypes: () -> Void
    /// Opens the shown week's "What I need" (#51); nil hides the item.
    var onOpenWhatINeed: ((Int64) -> Void)? = nil
    /// Makes the "Add this week's ingredients" sheet's ViewModel (#50); nil hides the item.
    var makeGroceriesVM: (() -> AddToGroceriesViewModel)? = nil
    @State private var groceriesVM: AddToGroceriesViewModel?
    @State private var groceriesSheet: AddToGroceriesViewModel?
    /// The week's .ics file once written, for the share sheet (#52).
    @State private var calendarURL: URL?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let state = vm.uiState
        VStack(spacing: 0) {
            // The header stays put while the days scroll under it.
            header(state)
                .padding(.horizontal, 20)
                .readableColumn()
            if let month = state.month {
                ScrollView {
                    MonthGrid(month: month, today: state.today, onSelect: vm.onMonthDaySelected)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 32)
                        .readableColumn()
                }
            } else {
                DayList(vm: vm, onOpenRecipe: onOpenRecipe)
            }
        }
        .screenBackground()
        // On a new day, today goes to the top again (the week starts on the day the app is
        // opened).
        .onAppear { vm.onScreenResumed() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { vm.onScreenResumed() }
        }
        // The week as an .ics file (#52): written and shared here, in the view layer; the
        // ViewModel only makes the text.
        .background(ShareSheetAnchor(item: calendarURL) {
            calendarURL = nil
            vm.onCalendarShared()
        })
        .onChange(of: state.calendarFile) { _, file in
            guard let file else { return }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(file.fileName)
            do {
                try Data(file.text.utf8).write(to: url, options: .atomic)
                calendarURL = url
            } catch {
                vm.onCalendarShared()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    // The week's own actions act on the week shown, so the month view leaves
                    // them out.
                    if let onOpenWhatINeed, state.month == nil {
                        Button(Strings.whatINeedTitle) { onOpenWhatINeed(vm.uiState.weekStart) }
                    }
                    if let makeGroceriesVM, state.month == nil {
                        Button(Strings.addWeekToGroceries) {
                            let groceries = groceriesVM ?? makeGroceriesVM()
                            groceriesVM = groceries
                            groceriesSheet = groceries
                            let start = vm.uiState.weekStart
                            groceries.loadWeek(start)
                        }
                    }
                    if state.month == nil {
                        Button(Strings.shareCalendar, action: vm.onShareCalendar)
                            .disabled(!state.hasMeals)
                    }
                    if state.month == nil {
                        Button(Strings.saveWeekAsMenu, action: vm.onSaveMenuStart)
                            .disabled(!state.hasMeals)
                            .accessibilityIdentifier("saveWeekAsMenu")
                        Button(Strings.applyMenu, action: vm.onPickMenuStart)
                            .accessibilityIdentifier("applyMenu")
                    }
                    Button(Strings.mealTypesTitle, action: onOpenMealTypes)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(Strings.moreOptions)
                .tooltipAnchor(.weekMenu, inToolbar: true)
            }
        }
        // The tooltips (#190); none over this screen's sheets, dialogs and snackbar. Outside
        // the toolbar, whose menu is an anchor too.
        .tooltipHost(.week, blocked: state.adding != nil || state.moving != nil || groceriesSheet != nil
            || state.removed != nil || calendarURL != nil || state.menus.saving || state.menus.picking
            || state.menus.renaming != nil || state.menus.deleting != nil)
        .overlay(alignment: .bottom) {
            if let removed = state.removed {
                Snackbar(message: Strings.removedFromPlan(removed.label), actionLabel: Strings.undo, action: vm.onUndoRemove)
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: state.removed == nil)
        .task(id: state.removed) {
            guard let removed = state.removed else { return }
            await SnackbarTimeout.run(pending: [removed.label], onTimeout: vm.onSnackbarDismissed)
        }
        .modifier(WeekMenusModifier(vm: vm))
        .sheet(item: $groceriesSheet) { groceries in
            AddToGroceriesSheet(vm: groceries)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: Binding(get: { state.adding != nil }, set: { if !$0 { vm.onAddDismissed() } })) {
            AddToDaySheet(vm: vm)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: Binding(get: { state.moving != nil }, set: { if !$0 { vm.onMoveDismissed() } })) {
            if let moving = vm.uiState.moving {
                PlanSheetContent(
                    title: Strings.moveMealTitle(moving.meal.title ?? moving.meal.note ?? ""),
                    days: moving.days,
                    today: vm.uiState.today,
                    selectedDay: moving.day,
                    onDaySelected: vm.onMoveDaySelected,
                    mealTypes: vm.uiState.mealTypes,
                    selectedMealTypeId: moving.mealTypeId,
                    onMealTypeSelected: vm.onMoveMealTypeSelected,
                    confirmLabel: Strings.moveToDay(PlanDayFormat.title(moving.day)),
                    confirmEnabled: true,
                    onConfirm: vm.onMoveConfirm
                )
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
        }
    }

    private func header(_ state: WeekUiState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                ScreenTitle(Strings.tabWeek)
                Spacer()
                Button(state.month == nil ? Strings.monthView : Strings.weekView) {
                    if state.month == nil { vm.onShowMonth() } else { vm.onShowWeek() }
                }
                .buttonStyle(TextActionStyle())
                .accessibilityIdentifier("toggleMonth")
                .tooltipAnchor(.weekMonth)
            }
            if let month = state.month {
                periodNavigation(
                    label: PlanDayFormat.monthTitle(month.monthStart),
                    labelIdentifier: "monthTitle",
                    previous: Strings.previousMonth,
                    next: Strings.nextMonth,
                    back: month.isThisMonth ? nil : Strings.thisMonth,
                    backIdentifier: "thisMonthButton",
                    onPrevious: vm.onPreviousMonth,
                    onNext: vm.onNextMonth,
                    onBack: vm.onThisMonth
                )
            } else {
                periodNavigation(
                    label: PlanDayFormat.weekRange(state.weekStart),
                    labelIdentifier: "weekRange",
                    previous: Strings.previousWeek,
                    next: Strings.nextWeek,
                    back: state.isThisWeek ? nil : Strings.today,
                    backIdentifier: "todayButton",
                    onPrevious: vm.onPreviousWeek,
                    onNext: vm.onNextWeek,
                    onBack: vm.onToday
                )
            }
            Hairline()
        }
        .padding(.top, 4)
    }

    /// ‹ label › and, away from the current week or month, a way back to it.
    private func periodNavigation(
        label: String, labelIdentifier: String, previous: String, next: String, back: String?,
        backIdentifier: String,
        onPrevious: @escaping () -> Void, onNext: @escaping () -> Void, onBack: @escaping () -> Void
    ) -> some View {
        HStack(spacing: 4) {
            Button(action: onPrevious) {
                Text("‹").textStyle(Typography.headlineSmall).frame(minWidth: 40, minHeight: 40)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(previous)
            // One line: the range names both weekdays since #232, so it shrinks a little
            // beside "Today" rather than wrap.
            Text(label)
                .textStyle(Typography.titleMedium)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .accessibilityIdentifier(labelIdentifier)
            Button(action: onNext) {
                Text("›").textStyle(Typography.headlineSmall).frame(minWidth: 40, minHeight: 40)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(next)
            Spacer()
            if let back {
                Button(back, action: onBack)
                    .buttonStyle(TextActionStyle())
                    .accessibilityIdentifier(backIdentifier)
            }
        }
        .foregroundStyle(Palette.onBackground)
    }
}

/// Every day the ViewModel holds, one section each (#232; Android's DayList), keyed by its epoch
/// day: the stack's one `ForEach`, so its ids are unique (#185). Free scrolling tells the
/// ViewModel the day at the top; a `scrollTo` from an arrow, "Today", a new day or the month is
/// carried out here. The position is the view's own state, starting on the ViewModel's top day,
/// so coming back from the month finds the days where they were.
private struct DayList: View {
    let vm: WeekViewModel
    let onOpenRecipe: (_ recipeId: Int64, _ servings: Int?) -> Void
    @State private var position: Int64?

    init(vm: WeekViewModel, onOpenRecipe: @escaping (_ recipeId: Int64, _ servings: Int?) -> Void) {
        self.vm = vm
        self.onOpenRecipe = onOpenRecipe
        _position = State(initialValue: vm.uiState.topDay)
    }

    var body: some View {
        let state = vm.uiState
        let typeNames = Dictionary(uniqueKeysWithValues: state.mealTypes.map { ($0.id, $0.name) })
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(state.allDays, id: \.self) { day in
                        DaySection(
                            vm: vm, day: day, isToday: day == state.today, meals: state.mealsOn(day),
                            typeNames: typeNames, onOpenRecipe: onOpenRecipe
                        )
                    }
                }
                .scrollTargetLayout()
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
                .readableColumn()
            }
            .scrollPosition(id: $position, anchor: .top)
            .accessibilityIdentifier("weekList")
            .onChange(of: position) { _, day in
                if let day { vm.onTopDayChanged(day) }
            }
            .onChange(of: state.scrollTo, initial: true) { _, target in
                guard let target else { return }
                if target.animate {
                    withAnimation(.easeInOut(duration: 0.35)) {
                        position = target.day
                    } completion: {
                        // A long way lands by estimated heights: settle it exactly.
                        proxy.scrollTo(target.day, anchor: .top)
                        vm.onScrollHandled(target.id, topDay: nil)
                    }
                } else {
                    position = target.day
                    vm.onScrollHandled(target.id, topDay: nil)
                    // A far jump lands by estimated heights; once the days around it are laid out,
                    // put it exactly at the top.
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(150))
                        proxy.scrollTo(target.day, anchor: .top)
                    }
                }
            }
        }
    }
}

/// One day: its heading, its meals, then its "+ Add".
private struct DaySection: View {
    let vm: WeekViewModel
    let day: Int64
    let isToday: Bool
    let meals: [PlannedMeal]
    let typeNames: [Int64: String]
    let onOpenRecipe: (_ recipeId: Int64, _ servings: Int?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(PlanDayFormat.title(day)).textStyle(Typography.titleMedium).foregroundStyle(Palette.onBackground)
                if isToday {
                    Text(Strings.today).textStyle(Typography.labelMedium).foregroundStyle(Palette.accentText)
                }
            }
            .padding(.top, 18)
            .padding(.bottom, 4)
            .accessibilityAddTraits(.isHeader)
            ForEach(meals) { meal in
                MealRow(meal: meal, mealTypeName: typeNames[meal.mealTypeId] ?? "") {
                    if let recipeId = meal.recipeId { onOpenRecipe(recipeId, meal.servings) }
                }
                .contextMenu {
                    Button { vm.onMoveStart(meal) } label: { Label(Strings.move, systemImage: "arrow.right") }
                    Button(role: .destructive) { vm.onRemove(meal) } label: {
                        Label(Strings.removeFromPlan, systemImage: "minus.circle")
                    }
                }
            }
            Button(Strings.addMeal) { vm.onAddToDay(day) }
                .buttonStyle(TextActionStyle())
                // The tooltip (#190) points at today's, the first day shown.
                .modifier(TodayAnchor(isToday: isToday))
                .padding(.leading, -12)
                .accessibilityIdentifier("addToDay-\(day)")
            Hairline()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The month view (#52): the locale's weekdays over whole weeks. A day with meals has a paprika
/// dot; today's date is paprika text; the days before and after the month are muted. Tapping any
/// day opens its week.
private struct MonthGrid: View {
    let month: MonthUiState
    let today: Int64
    let onSelect: (Int64) -> Void

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 7)

    var body: some View {
        if !month.days.isEmpty {
            LazyVGrid(columns: columns, spacing: 0) {
                // Not the days' own ids (#185): the grid pools every ForEach's ids, and the
                // first row of cells, sharing them, didn't draw.
                ForEach(month.days.prefix(7).map { (id: "weekday-\($0.day)", day: $0.day) }, id: \.id) { heading in
                    Text(PlanDayFormat.shortWeekday(heading.day))
                        .textStyle(Typography.labelMedium)
                        .foregroundStyle(Palette.muted)
                        .padding(.bottom, 4)
                        .accessibilityHidden(true)
                }
                ForEach(month.days) { cell in
                    Button { onSelect(cell.day) } label: {
                        VStack(spacing: 4) {
                            Text(PlanDayFormat.dayOfMonth(cell.day))
                                .textStyle(Typography.titleMedium)
                                .foregroundStyle(
                                    cell.day == today ? Palette.accentText
                                        : cell.inMonth ? Palette.onBackground : Palette.muted.opacity(0.6)
                                )
                            Circle()
                                .fill(cell.mealCount > 0 ? Palette.primary : Color.clear)
                                .frame(width: 6, height: 6)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(
                        cell.mealCount > 0 ? Strings.monthDayPlanned(PlanDayFormat.title(cell.day)) : PlanDayFormat.title(cell.day)
                    )
                    .accessibilityIdentifier("monthDay-\(cell.day)")
                }
            }
            .padding(.top, 12)
        }
    }
}

/// A planned meal: its meal type above the recipe (thumbnail, title, servings) or the note.
private struct MealRow: View {
    let meal: PlannedMeal
    let mealTypeName: String
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                if meal.recipeId != nil {
                    CachedAsyncImage(url: meal.imageUrl.flatMap(URL.init(string:))) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Color.clear
                    }
                        .frame(width: 44, height: 44)
                        .background(Palette.hairline)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text(mealTypeName).textStyle(Typography.labelMedium).foregroundStyle(Palette.muted)
                    if meal.recipeId != nil {
                        Text(meal.title ?? "")
                            .textStyle(Typography.bodyLargeBold)
                            .foregroundStyle(Palette.onBackground)
                            .lineLimit(2)
                    } else {
                        Text(meal.note ?? "")
                            .textStyle(Typography.bodyLarge)
                            .italic()
                            .foregroundStyle(Palette.onBackground)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let servings = meal.servings {
                    Text(Strings.servings(servings)).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                }
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("meal-\(meal.id)")
    }
}

/// "+ Add" on a day: a meal type, then one of your recipes (history, searchable) or, with
/// anything typed, that text as a note. Choosing adds at once.
private struct AddToDaySheet: View {
    let vm: WeekViewModel

    var body: some View {
        if let adding = vm.uiState.adding {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SectionHeading(Strings.addToDayTitle(PlanDayFormat.title(adding.day))).padding(.bottom, 12)
                    MealTypeChoices(types: vm.uiState.mealTypes, selected: adding.mealTypeId, onSelect: vm.onAddMealTypeSelected)
                        .padding(.bottom, 12)
                    OutlinedField(
                        label: Strings.searchOrNote,
                        text: Binding(get: { adding.query }, set: vm.onAddQueryChange)
                    )
                    .accessibilityIdentifier("addSearch")
                    let typed = adding.query.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !typed.isEmpty {
                        Button(Strings.addAsNote(typed), action: vm.onAddNote)
                            .buttonStyle(TextActionStyle())
                            .padding(.leading, -12)
                    }
                    if let results = adding.results, results.isEmpty, typed.isEmpty {
                        Text(Strings.homeEmptyHint)
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(Palette.muted)
                            .padding(.top, 8)
                    }
                    ForEach(adding.results ?? []) { recipe in
                        Button { vm.onAddRecipe(recipe.id) } label: {
                            Text(recipe.title)
                                .textStyle(Typography.bodyLarge)
                                .foregroundStyle(Palette.onBackground)
                                .lineLimit(2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 10)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        Hairline()
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
                .readableColumn()
            }
            .scrollDismissesKeyboard(.interactively)
            .presentationBackground(Palette.background)
        }
    }
}

/// Today's "+ Add", which the "+ Add" tooltip (#190) points at.
private struct TodayAnchor: ViewModifier {
    let isToday: Bool

    func body(content: Content) -> some View {
        if isToday { content.tooltipAnchor(.weekAdd) } else { content }
    }
}
