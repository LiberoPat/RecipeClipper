import SwiftUI
import UIKit

/// The review of a week of recipes shared in (#352; Android's `MealPlanReviewScreen`): each recipe
/// ticked, with the day it will be planned on to change. Save keeps the ticked ones; `onFinished`
/// opens Week when any was planned (`true`), else goes back. Back discards: nothing is saved before
/// Save.
struct MealPlanReviewScreen: View {
    let vm: MealPlanReviewViewModel
    let onFinished: (_ planned: Bool) -> Void

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenTitle(Strings.mealPlanTitle, style: Typography.headlineSmall)
                Text(state.meals ? Strings.mealPlanIntroMeals : Strings.mealPlanIntro)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                if state.notFound > 0 {
                    // Recipes the answer named by a link that matches nothing saved: left out, never made up (#367).
                    Text(Strings.mealPlanNotFound(state.notFound))
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.muted)
                        .accessibilityIdentifier("mealplan.notfound")
                }
                if state.cutOff {
                    // The answer stopped mid-recipe (#352): say so, and offer a message to ask for the rest.
                    VStack(alignment: .leading, spacing: 6) {
                        Text(Strings.mealPlanCutOff)
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(Palette.muted)
                            .accessibilityIdentifier("mealplan.cutoff")
                        if state.copied {
                            Text(Strings.cutOffCopied)
                                .textStyle(Typography.bodyMedium)
                                .foregroundStyle(Palette.muted)
                                .accessibilityIdentifier("mealplan.cutoff.copied")
                        } else {
                            Button(Strings.cutOffCopyAction) {
                                UIPasteboard.general.string = vm.followUp(Strings.cutOffWords)
                                vm.onCopied()
                            }
                            .buttonStyle(TextActionStyle())
                            .accessibilityIdentifier("mealplan.cutoff.copy")
                        }
                    }
                }
                if state.kept > 0 {
                    HStack {
                        Text(Strings.mealPlanKept(state.kept))
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(Palette.onBackground)
                            .accessibilityIdentifier("mealplan.kept")
                        Spacer()
                        if state.planned > 0 {
                            Button(Strings.mealPlanOpenWeek) { onFinished(true) }
                                .buttonStyle(TextActionStyle())
                        }
                    }
                }
                if state.saveFailed {
                    Text(Strings.editErrorSaveFailed)
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.error)
                }
                if state.overLimit { limitNotice(state) }
                if state.loading {
                    ProgressView().tint(Palette.primary)
                } else {
                    ForEach(state.items) { item in
                        row(item, days: state.days, meals: state.meals ? state.mealTypes : [], nudge: state.nudges[item.index])
                        Divider().overlay(Palette.hairline)
                    }
                    Text(Strings.mealPlanKeeping(state.tickedCount, of: state.items.count))
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.muted)
                        .accessibilityIdentifier("mealplan.count")
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(Strings.save, action: vm.onSave)
                    .disabled(state.loading || state.saving || state.savingCount == 0)
                    .accessibilityIdentifier("mealplan.save")
            }
        }
        .onChange(of: state.finished) { _, finished in
            if finished { onFinished(vm.uiState.planned > 0) }
        }
    }

    /// The free library has room for only some of what is ticked: how many, and Unlock (#107).
    /// Never removes anything.
    private func limitNotice(_ state: MealPlanReviewUiState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(Strings.mealPlanRoom(state.room ?? 0))
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.onBackground)
            if let notice = state.unlockNotice {
                Text(Strings.unlockNotice(notice))
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.error)
            }
            Button(Strings.unlock, action: vm.onUnlock)
                .buttonStyle(TextActionStyle())
                .accessibilityIdentifier("mealplan.unlock")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("mealplan.limit")
    }

    private func row(_ item: ReviewItem, days: [Int64], meals: [MealType], nudge: FreshNudge?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center, spacing: 12) {
                Button { vm.onTick(item.index) } label: {
                    HStack(spacing: 12) {
                        Image(systemName: item.ticked ? "checkmark.square.fill" : "square")
                            .foregroundStyle(item.ticked ? Palette.primary : Palette.muted)
                            .imageScale(.large)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                                .textStyle(Typography.titleMedium)
                                .foregroundStyle(Palette.onBackground)
                                .lineLimit(2)
                            if item.existing {
                                Text(Strings.mealPlanAlready)
                                    .textStyle(Typography.bodySmall)
                                    .foregroundStyle(Palette.accentText)
                                    .accessibilityIdentifier("mealplan.already.\(item.index)")
                            } else if !item.preview.isEmpty {
                                Text(item.preview)
                                    .textStyle(Typography.bodySmall)
                                    .foregroundStyle(Palette.muted)
                                    .lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(item.ticked ? [.isSelected] : [])
                .accessibilityIdentifier("mealplan.row.\(item.index)")
                dayMenu(item, days: days)
            }
            if !meals.isEmpty {
                mealMenu(item, types: meals).padding(.leading, 40)
            }
            if item.existing && item.ticked && item.day == nil {
                Text(Strings.mealPlanPickDay)
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.muted)
                    .padding(.leading, 40)
                    .accessibilityIdentifier("mealplan.pickday.\(item.index)")
            }
            if item.passed && item.day == nil {
                Text(Strings.mealPlanDayPassed)
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.muted)
                    .padding(.leading, 40)
            }
            if item.ticked && !item.fits {
                Text(Strings.mealPlanWontFit)
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.error)
                    .padding(.leading, 40)
                    .accessibilityIdentifier("mealplan.wontfit.\(item.index)")
            }
            if let nudge {
                FreshNudgeNote(nudge: nudge) { vm.onSwap(item.index) }
                    .padding(.leading, 40)
            }
        }
        .padding(.vertical, 6)
    }

    /// A row's meal (#392), a button over a menu of the cook's meal types: the one it is planned under.
    private func mealMenu(_ item: ReviewItem, types: [MealType]) -> some View {
        let label = types.first { $0.id == item.mealTypeId }?.name ?? types[0].name
        return Menu {
            ForEach(types) { type in
                Button(type.name) { vm.onMeal(item.index, type.id) }
            }
        } label: {
            Text(label)
                .textStyle(Typography.labelLarge)
                .foregroundStyle(Palette.primary)
        }
        .accessibilityLabel("\(Strings.mealPlanMealFor(item.name)): \(label)")
        .accessibilityIdentifier("mealplan.meal.\(item.index)")
    }

    /// A row's day, a button over a menu: today and the next 13 days, the answer's own later day,
    /// or no day.
    private func dayMenu(_ item: ReviewItem, days: [Int64]) -> some View {
        let options = Array(Set(days + (item.day.map { [$0] } ?? []))).sorted()
        let label = item.day.map(PlanDayFormat.title) ?? Strings.mealPlanDayNone
        return Menu {
            ForEach(options, id: \.self) { day in
                Button(PlanDayFormat.title(day)) { vm.onDay(item.index, day) }
            }
            Button(Strings.mealPlanDayDontPlan) { vm.onDay(item.index, nil) }
        } label: {
            Text(label)
                .textStyle(Typography.labelLarge)
                .foregroundStyle(Palette.primary)
        }
        .accessibilityLabel("\(Strings.mealPlanDayFor(item.name)): \(label)")
        .accessibilityIdentifier("mealplan.day.\(item.index)")
    }
}
