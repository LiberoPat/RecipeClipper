import Foundation

/// How a ViewModel waits (the history debounce, the timer tick). Injected so a test can drive
/// time deterministically instead of really waiting; main-actor so the wait and the code that
/// resumes after it run on the same executor as the ViewModel.
typealias Sleep = @MainActor (Duration) async throws -> Void

enum Sleeps {
    static let real: Sleep = { try await Task.sleep(for: $0) }
}
