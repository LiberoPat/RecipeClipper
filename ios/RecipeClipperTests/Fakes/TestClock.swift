import Foundation
@testable import RecipeClipper

/// Virtual time for ViewModel tests: `now()` is a number the test moves, and `sleep` suspends
/// until the test advances past its wake-up time — the counterpart of kotlinx-coroutines-test's
/// virtual scheduler, so a timer's wall-clock deadline and the tick loop's waits agree.
final class TestClock: Clock, @unchecked Sendable {
    private struct Sleeper {
        let id: Int
        let wake: Int64
        let continuation: CheckedContinuation<Void, Error>
    }

    private let lock = NSLock()
    private var current: Int64
    private var sleepers: [Sleeper] = []
    private var nextId = 0
    /// Sleepers `advance` has resumed whose code hasn't started running again yet. Yielding a
    /// fixed number of times is not a barrier: the resumed job can still be queued behind the
    /// test's yields, and `advance` would then see no sleepers, stop early and jump the clock
    /// to the target before the woken tick runs (seen as a timer reading 3 s instead of 4).
    private var woken: Set<Int> = []

    init(now: Int64 = 1_000_000) {
        current = now
    }

    func now() -> Int64 {
        lock.withLock { current }
    }

    /// Pass this as a ViewModel's `sleep`.
    var sleep: Sleep {
        { [self] duration in try await self.sleep(for: duration) }
    }

    /// Whether anything is waiting on the clock.
    var hasSleepers: Bool { lock.withLock { !sleepers.isEmpty } }

    @MainActor
    func sleep(for duration: Duration) async throws {
        let (seconds, attoseconds) = duration.components
        let wake = now() + seconds * 1000 + attoseconds / 1_000_000_000_000_000
        let id = lock.withLock { () -> Int in nextId += 1; return nextId }
        // Back in the sleeper's code (woken or cancelled): `advance` may move on.
        defer { lock.withLock { _ = woken.remove(id) } }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                lock.withLock { sleepers.append(Sleeper(id: id, wake: wake, continuation: continuation)) }
            }
        } onCancel: {
            let cancelled = self.lock.withLock { () -> Sleeper? in
                guard let index = self.sleepers.firstIndex(where: { $0.id == id }) else { return nil }
                return self.sleepers.remove(at: index)
            }
            cancelled?.continuation.resume(throwing: CancellationError())
        }
    }

    /// Moves time forward by `ms`, waking every sleeper due on the way in order and letting
    /// the woken code run (and sleep again) before moving on — `advanceTimeBy` + `runCurrent`.
    @MainActor
    func advance(by ms: Int64) async {
        let target = now() + ms
        while true {
            // Woken code is main-actor (the ViewModels, their tick loops), so yielding is
            // enough for it to run and sleep again; a full settle only at the end.
            for _ in 0 ..< 10 { await Task.yield() }
            let due: [Sleeper] = lock.withLock {
                guard let earliest = sleepers.map(\.wake).min(), earliest <= target else { return [] }
                current = max(current, earliest) // never backwards, even after a jump
                let woken = sleepers.filter { $0.wake <= earliest }
                sleepers.removeAll { $0.wake <= earliest }
                return woken
            }
            if due.isEmpty { break }
            lock.withLock { due.forEach { woken.insert($0.id) } }
            due.forEach { $0.continuation.resume() }
            await waitForWokenToRun()
        }
        lock.withLock { current = target }
        await settleMain()
    }

    /// Waits until every sleeper just resumed is running again. Woken code is main-actor, so
    /// by the time it is back past its `sleep` it has also run up to its next suspension
    /// (usually sleeping again). Bounded in real time so a bug shows as a failed assertion,
    /// not a hung test host.
    @MainActor
    private func waitForWokenToRun() async {
        var spins = 0
        while lock.withLock({ !woken.isEmpty }), spins < 2_000 {
            spins += 1
            if spins <= 100 {
                await Task.yield()
            } else {
                try? await Task.sleep(nanoseconds: 1_000_000)
            }
        }
    }

    /// Moves time without waking anyone, as if the process were frozen; whoever is due wakes
    /// on the next `advance`, all at once.
    func jump(by ms: Int64) {
        lock.withLock { current += ms }
    }

    /// Keeps advancing until nothing is waiting — `advanceUntilIdle` for bounded countdowns.
    @MainActor
    func runUntilIdle(limitMs: Int64 = 3_600_000) async {
        let start = now()
        await settleMain()
        while hasSleepers, now() - start < limitMs {
            let next = lock.withLock { sleepers.map(\.wake).min() ?? current }
            await advance(by: max(0, next - now()))
        }
    }
}
