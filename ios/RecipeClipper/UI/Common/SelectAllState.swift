import Foundation

/// The state of a "Select all" row (#406) over a checklist: nothing ticked, some, or every row.
/// Tapping it when `.on` unticks every row; in any other state it ticks every row. Android's
/// twin is `SelectAllState` in `ui/common`.
enum SelectAllState: Equatable {
    case off, mixed, on

    /// The row shows only when there is more than one thing to tick.
    static let minRows = 2

    static func of(ticked: Int, total: Int) -> SelectAllState {
        if total > 0 && ticked >= total { return .on }
        if ticked <= 0 { return .off }
        return .mixed
    }

    /// What a tap does: untick everything when everything is ticked, else tick everything.
    var ticksAll: Bool { self != .on }
}
