import ActivityKit
import Foundation

/// One running cook-mode timer as a Live Activity (#309), on the lock screen and in the Dynamic
/// Island. Compiled into the app, which starts, updates and ends it (`ActivityKitTimerDisplay`),
/// and into the widget extension, which draws it (`TimerLiveActivity`).
///
/// The activity counts down to `endsAt` by itself (`Text(timerInterval:)`), so like the saved
/// cook progress it never updates per tick; `staleDate` is the deadline, after which it reads
/// "Time's up" until the app ends it.
struct TimerActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// The timer's deadline, the same one cook progress saves.
        var endsAt: Date
    }

    let recipeId: Int64
    let recipeTitle: String
    /// Zero-based, like `StepAlarm.step`; shown one-based.
    let step: Int
}
