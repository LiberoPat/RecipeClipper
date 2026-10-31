import ActivityKit
import SwiftUI
import WidgetKit

/// A running step timer on the lock screen and in the Dynamic Island (#309): the step, the
/// recipe and the time left, counted down by the system from the saved deadline. Past it (the
/// activity's stale date) it reads "Time's up: step N" until the app ends it. A tap opens the
/// recipe in cook mode.
struct TimerLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TimerActivityAttributes.self) { context in
            LockScreenTimer(context: context)
                .padding(16)
                .activityBackgroundTint(Palette.background)
                .widgetURL(AppLinks.cook(recipeId: context.attributes.recipeId))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "timer").foregroundStyle(Palette.accentText).environment(\.colorScheme, .dark)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Countdown(context: context).font(.title2.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.bottom) {
                    TimerLabels(context: context, inverted: true)
                }
            } compactLeading: {
                Image(systemName: "timer").foregroundStyle(Palette.accentText).environment(\.colorScheme, .dark)
            } compactTrailing: {
                Countdown(context: context).frame(maxWidth: 56)
            } minimal: {
                Image(systemName: "timer").foregroundStyle(Palette.accentText).environment(\.colorScheme, .dark)
            }
            .widgetURL(AppLinks.cook(recipeId: context.attributes.recipeId))
        }
    }
}

private struct LockScreenTimer: View {
    let context: ActivityViewContext<TimerActivityAttributes>

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            TimerLabels(context: context, inverted: false)
            Spacer(minLength: 8)
            Countdown(context: context)
                .font(WidgetFont.fraunces(34, .medium).monospacedDigit())
                .foregroundStyle(Palette.accentText)
        }
    }
}

/// "Step 3 timer" over the recipe's name; "Time's up: step 3" once the deadline has passed.
private struct TimerLabels: View {
    let context: ActivityViewContext<TimerActivityAttributes>
    /// The Dynamic Island is always dark.
    let inverted: Bool

    var body: some View {
        let step = context.attributes.step + 1
        VStack(alignment: .leading, spacing: 2) {
            Text(context.isStale ? WidgetStrings.timerNotificationTitle(step: step) : WidgetStrings.timerRunningTitle(step: step))
                .font(WidgetFont.fraunces(17, .semibold))
                .foregroundStyle(inverted ? Color.white : Palette.onBackground)
            Text(context.attributes.recipeTitle)
                .font(WidgetFont.karla(15, .regular))
                .foregroundStyle(inverted ? Color.white.opacity(0.7) : Palette.muted)
                .lineLimit(1)
        }
    }
}

/// The time left, counted by the system: nothing is sent per tick.
private struct Countdown: View {
    let context: ActivityViewContext<TimerActivityAttributes>

    var body: some View {
        let endsAt = context.state.endsAt
        Text(timerInterval: min(Date(), endsAt)...endsAt, countsDown: true)
            .multilineTextAlignment(.trailing)
            .monospacedDigit()
    }
}
