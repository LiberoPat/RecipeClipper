import SwiftUI
import UIKit

private enum StepStatus { case done, current, upcoming }

/// A highlighted scroll, not a pager: cooking isn't linear, so the next step has to be
/// glanceable before it is current, and a scroll still works when a source gives three
/// paragraph-blobs instead of twelve clean steps.
struct CookView: View {
    let content: RecipeSuccess
    let state: RecipeUiState
    let vm: RecipeViewModel

    var body: some View {
        let cook = state.cook
        let steps = content.instructions

        VStack(spacing: 0) {
            CookTopBar(
                title: content.recipe.name,
                position: Strings.cookPosition(cook.currentStep + 1, of: steps.count),
                onExit: vm.onCookExit
            )
            IngredientsBar(content: content, state: state, vm: vm)
                // Sized ahead of the steps' scroll view, so an expanded list gets its full
                // allowance at the larger text sizes instead of an even share of the height.
                .layoutPriority(1)

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(Array(steps.indices), id: \.self) { index in
                            CookStep(
                                index: index,
                                // Chef mode (#100): the short version, unless asked for as written.
                                text: content.shownStep(index, asWritten: state.asWrittenSteps),
                                amounts: content.shownStepParts(index, asWritten: state.asWrittenSteps),
                                shortToggle: !content.hasShortStep(index) ? nil
                                    : state.asWrittenSteps.contains(index) ? Strings.stepShowShort : Strings.stepShowAsWritten,
                                status: index == cook.currentStep ? .current
                                    : cook.doneSteps.contains(index) ? .done : .upcoming,
                                timerSeconds: index < content.stepTimerSeconds.count ? content.stepTimerSeconds[index] : nil,
                                // A step has a timer only when the app has the recipe's words.
                                timerWords: content.words ?? .english,
                                timer: cook.timers[index],
                                isLast: index == steps.count - 1,
                                vm: vm
                            )
                            .id(index)
                        }
                    }
                    .padding(.horizontal, 20)
                    .readableColumn()
                    .padding(.top, 12)
                    .padding(.bottom, 64)
                }
                .onAppear { proxy.scrollTo(cook.currentStep, anchor: .top) }
                .onChange(of: cook.currentStep) { _, step in
                    withAnimation { proxy.scrollTo(step, anchor: .top) }
                }
            }
        }
        // Cook mode holds the screen awake: nobody wants it dimming with flour on their hands.
        .keepsScreenOn()
    }
}

/// Android's FLAG_KEEP_SCREEN_ON, scoped to cook mode being on screen. A count of visible
/// cook views rather than a plain on/off: popping one recipe screen that was cooking back to
/// another that is cooking runs the lower one's onAppear *before* the upper one's
/// onDisappear, and a boolean set in each would end up off while cook mode is showing.
/// The idle timer is also released while the app is not active and taken back on return.
@MainActor
enum KeepScreenOn {
    private(set) static var holders = 0
    private(set) static var appActive = true

    static func acquire() { holders += 1; apply() }
    static func release() { holders = max(0, holders - 1); apply() }
    static func setAppActive(_ active: Bool) { appActive = active; apply() }

    /// What `isIdleTimerDisabled` should be.
    static var wanted: Bool { holders > 0 && appActive }

    private static func apply() {
        UIApplication.shared.isIdleTimerDisabled = wanted
    }

    /// For tests.
    static func reset() { holders = 0; appActive = true; apply() }
}

private struct KeepsScreenOn: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .onAppear {
                KeepScreenOn.setAppActive(scenePhase == .active)
                KeepScreenOn.acquire()
            }
            .onDisappear { KeepScreenOn.release() }
            .onChange(of: scenePhase) { _, phase in KeepScreenOn.setAppActive(phase == .active) }
    }
}

extension View {
    func keepsScreenOn() -> some View { modifier(KeepsScreenOn()) }
}

private struct CookTopBar: View {
    let title: String
    let position: String
    let onExit: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        // At the accessibility sizes Exit and the counter stack: side by side, German's
        // "Beenden" and "Schritt 1 von 2" were each broken mid-word.
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 0))
            : AnyLayout(HStackLayout(spacing: 8))
        layout {
            Button(Strings.exit, action: onExit)
                .buttonStyle(TextActionStyle(color: Palette.muted))
            // At the accessibility sizes the title goes: it is the least useful of the three
            // while cooking (it was on the screen you came from).
            if !dynamicTypeSize.isAccessibilitySize {
                Text(title)
                    .textStyle(Typography.titleSmall)
                    .foregroundStyle(Palette.onBackground)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Text(position)
                .textStyle(Typography.labelMedium)
                .foregroundStyle(Palette.muted)
                .padding(.leading, dynamicTypeSize.isAccessibilitySize ? 12 : 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 8)
        .padding(.trailing, 20)
        // Exit's own 12pt padding puts its text on the 20pt gutter, so this lines up with
        // the column below.
        .readableColumn()
        .padding(.vertical, 4)
    }
}

/// Ingredients stay one tap away, folded up so the steps own the screen.
private struct IngredientsBar: View {
    let content: RecipeSuccess
    let state: RecipeUiState
    let vm: RecipeViewModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// Room for more ingredients when each one is bigger, capped so the steps keep the rest.
    @ScaledMetric(relativeTo: .body) private var scaledListHeight: CGFloat = 260
    private var listHeight: CGFloat { min(scaledListHeight, 420) }

    var body: some View {
        let expanded = state.cook.ingredientsExpanded
        VStack(spacing: 0) {
            Hairline()
            Button(action: vm.onIngredientsToggle) {
                HStack(spacing: 0) {
                    // The count drops under the heading at the accessibility sizes instead of
                    // wrapping mid-phrase beside it.
                    let stacked = dynamicTypeSize.isAccessibilitySize
                    let count = content.ingredients.count
                    let heading = stacked
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 0))
                        : AnyLayout(HStackLayout(spacing: 0))
                    heading {
                        Text(Strings.headingIngredients)
                            .textStyle(Typography.titleSmall)
                            .foregroundStyle(Palette.onBackground)
                        Text(stacked ? Strings.cookIngredientsCountOwnLine(count) : Strings.cookIngredientsCount(count))
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(Palette.muted)
                    }
                    Spacer(minLength: 8)
                    Text(expanded ? "▴" : "▾")
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.muted)
                }
                .padding(.horizontal, 20)
                .readableColumn()
                .padding(.vertical, 14)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(expanded ? Strings.hideIngredients : Strings.showIngredients)

            if expanded {
                let list = VStack(spacing: 0) {
                    ForEach(Array(content.ingredients.enumerated()), id: \.offset) { index, ingredient in
                        IngredientRow(
                            text: ingredient,
                            checked: state.checkedIngredients.contains(index),
                            onCheckedChange: { vm.onIngredientChecked(index, $0) }
                        )
                    }
                }
                .padding(.horizontal, 20)
                .readableColumn()
                .padding(.bottom, 8)

                ViewThatFits(in: .vertical) {
                    list
                    ScrollView { list }
                }
                .frame(maxHeight: listHeight)
            }
            Hairline()
        }
    }
}

private struct CookStep: View {
    let index: Int
    let text: String
    let amounts: [StepAmounts.Part]?
    /// Chef mode (#100): the current step's "As written" / "Short version" button; nil: none. A
    /// tap on a step already makes it current, so the switch is a small button on the card.
    let shortToggle: String?
    let status: StepStatus
    let timerSeconds: Int?
    let timerWords: LanguageWords
    let timer: StepTimer?
    let isLast: Bool
    let vm: RecipeViewModel
    @ScaledMetric(relativeTo: .headline) private var numberColumn: CGFloat = 32
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        if status == .current {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline) {
                    Text(Strings.cookStepLabel(index + 1))
                        .textStyle(Typography.labelSmall)
                        .foregroundStyle(Palette.accentText)
                    Spacer()
                    if let shortToggle {
                        Button(shortToggle) { vm.onStepAsWrittenToggle(index) }
                            .textStyle(Typography.labelMedium)
                            .foregroundStyle(Palette.accentText)
                    }
                }
                .padding(.bottom, 8)
                stepText(text, amounts, accent: Palette.accentText)
                    .textStyle(Typography.cookStep)
                    .foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if timerSeconds != nil || timer != nil {
                    CurrentTimer(step: index, timerSeconds: timerSeconds, timerWords: timerWords, timer: timer, vm: vm)
                        .padding(.top, 16)
                }
                Button(isLast ? Strings.cookDoneFinish : Strings.cookDoneNext, action: vm.onStepDone)
                    .buttonStyle(PrimaryButtonStyle(minHeight: 52, fillWidth: true))
                    .padding(.top, 20)
            }
            .padding(20)
            .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Palette.primary, lineWidth: 2))
        } else {
            let done = status == .done
            Button { vm.onStepSelected(index) } label: {
                // As in the reading view: the number goes above the step at the
                // accessibility sizes rather than taking a third of the width beside it.
                let stacked = dynamicTypeSize.isAccessibilitySize
                let layout = stacked
                    ? AnyLayout(VStackLayout(alignment: .leading, spacing: 0))
                    : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 0))
                VStack(alignment: .leading, spacing: 4) {
                    layout {
                        Text("\(index + 1)")
                            .textStyle(Typography.titleMedium)
                            .foregroundStyle(done ? Palette.muted : Palette.accentText)
                            .frame(width: stacked ? nil : numberColumn, alignment: .leading)
                        // A done step is dimmed as a whole; its amounts keep only their weight.
                        stepText(text, amounts, accent: done ? nil : Palette.accentText)
                            .textStyle(Typography.bodyLarge)
                            .strikethrough(done)
                            .foregroundStyle(Palette.muted)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    // A timer still running on a step you've moved on from stays visible:
                    // steps overlap.
                    if let timer {
                        Text(Strings.timerRunning(timer.finished ? Strings.timesUp : StepTimers.clock(timer.remainingSeconds)))
                            .textStyle(Typography.labelLarge)
                            .monospacedDigit()
                            .foregroundStyle(Palette.accentText)
                            .padding(.leading, stacked ? 0 : numberColumn)
                    }
                }
                .padding(.horizontal, 4)
                .padding(.vertical, 6)
                .opacity(done ? 0.45 : 1)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Strings.goToStep(index + 1))
        }
    }
}

private struct CurrentTimer: View {
    let step: Int
    let timerSeconds: Int?
    let timerWords: LanguageWords
    let timer: StepTimer?
    let vm: RecipeViewModel

    var body: some View {
        if let timer {
            // The clock and its buttons share a row while they fit; at the larger sizes the
            // buttons go under the clock (and under each other) rather than squeezing it.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 0) {
                    clock(timer).fixedSize()
                    Spacer()
                    buttons(timer).fixedSize()
                }
                VStack(alignment: .leading, spacing: 4) {
                    clock(timer)
                    HStack(spacing: 0) { buttons(timer).fixedSize() }.padding(.leading, -12)
                }
                VStack(alignment: .leading, spacing: 4) {
                    clock(timer)
                    VStack(alignment: .leading, spacing: 0) { buttons(timer) }.padding(.leading, -12)
                }
            }
        } else if let timerSeconds {
            Button(Strings.timerStart(StepTimers.label(timerSeconds, words: timerWords))) { vm.onTimerStart(step) }
                .buttonStyle(OutlinedActionStyle())
        }
    }

    private func clock(_ timer: StepTimer) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(StepTimers.clock(timer.remainingSeconds))
                .textStyle(Typography.timerClock)
                .foregroundStyle(timer.finished ? Palette.accentText : Palette.onBackground)
            if timer.finished {
                Text(Strings.timesUp)
                    .textStyle(Typography.labelMedium)
                    .foregroundStyle(Palette.accentText)
            }
        }
    }

    @ViewBuilder
    private func buttons(_ timer: StepTimer) -> some View {
        if !timer.finished {
            Button(timer.running ? Strings.pause : Strings.resume) { vm.onTimerToggle(step) }
                .buttonStyle(TextActionStyle())
        }
        Button(Strings.reset) { vm.onTimerReset(step) }
            .buttonStyle(TextActionStyle(color: Palette.muted))
    }
}
