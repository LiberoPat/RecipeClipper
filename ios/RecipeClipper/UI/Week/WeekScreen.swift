import SwiftUI

/// The Week tab (#49; Android's WeekScreen): ‹ week › with "This week", then the seven days
/// from the locale's first day of the week, each with its meals and a "+ Add". Tapping a recipe
/// opens it at the planned servings; long-pressing a meal offers Move and Remove (Remove can be
/// undone).
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

    var body: some View {
        let state = vm.uiState
        let typeNames = Dictionary(uniqueKeysWithValues: state.mealTypes.map { ($0.id, $0.name) })
        ScrollViewReader { proxy in
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                header(state)
                if let month = state.month {
                    MonthGrid(month: month, today: state.today, onSelect: vm.onMonthDaySelected)
                }
                ForEach(state.month == nil ? state.days : []) { weekDay in
                    dayHeader(weekDay.day, isToday: weekDay.day == state.today)
                        .id("day-\(weekDay.day)")
                    ForEach(weekDay.meals) { meal in
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
                    Button(Strings.addMeal) { vm.onAddToDay(weekDay.day) }
                        .buttonStyle(TextActionStyle())
                        .padding(.leading, -12)
                        .accessibilityIdentifier("addToDay-\(weekDay.day)")
                    Hairline()
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
            .readableColumn()
        }
        // After a tap in the month view: scroll to that day once its week has loaded.
        .onChange(of: FocusKey(day: state.focusDay, loaded: state.days.map(\.day)), initial: true) { _, key in
            guard let day = key.day, key.loaded.contains(day) else { return }
            proxy.scrollTo("day-\(day)", anchor: .top)
            vm.onFocusHandled()
        }
        }
        .screenBackground()
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
                    Button(Strings.mealTypesTitle, action: onOpenMealTypes)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(Strings.moreOptions)
            }
        }
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
            }
            if let month = state.month {
                periodNavigation(
                    label: PlanDayFormat.monthTitle(month.monthStart),
                    labelIdentifier: "monthTitle",
                    previous: Strings.previousMonth,
                    next: Strings.nextMonth,
                    back: month.isThisMonth ? nil : Strings.thisMonth,
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
                    back: state.isThisWeek ? nil : Strings.thisWeek,
                    onPrevious: vm.onPreviousWeek,
                    onNext: vm.onNextWeek,
                    onBack: vm.onThisWeek
                )
            }
            Hairline()
        }
        .padding(.top, 4)
    }

    /// ‹ label › and, away from the current week or month, a way back to it.
    private func periodNavigation(
        label: String, labelIdentifier: String, previous: String, next: String, back: String?,
        onPrevious: @escaping () -> Void, onNext: @escaping () -> Void, onBack: @escaping () -> Void
    ) -> some View {
        HStack(spacing: 4) {
            Button(action: onPrevious) {
                Text("‹").textStyle(Typography.headlineSmall).frame(minWidth: 40, minHeight: 40)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(previous)
            Text(label)
                .textStyle(Typography.titleMedium)
                .accessibilityIdentifier(labelIdentifier)
            Button(action: onNext) {
                Text("›").textStyle(Typography.headlineSmall).frame(minWidth: 40, minHeight: 40)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(next)
            Spacer()
            if let back {
                Button(back, action: onBack).buttonStyle(TextActionStyle())
            }
        }
        .foregroundStyle(Palette.onBackground)
    }

    private func dayHeader(_ day: Int64, isToday: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(PlanDayFormat.title(day)).textStyle(Typography.titleMedium).foregroundStyle(Palette.onBackground)
            if isToday {
                Text(Strings.today).textStyle(Typography.labelMedium).foregroundStyle(Palette.accentText)
            }
        }
        .padding(.top, 18)
        .padding(.bottom, 4)
        .accessibilityAddTraits(.isHeader)
    }
}

/// What the week view waits for before scrolling to a day tapped in the month view.
private struct FocusKey: Equatable {
    let day: Int64?
    let loaded: [Int64]
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
                ForEach(month.days.prefix(7).map { $0.day }, id: \.self) { day in
                    Text(PlanDayFormat.shortWeekday(day))
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
                        Text(Strings.historyEmpty)
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
