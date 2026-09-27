import Foundation

/// The `recipes.cookState` column: a `CookProgress` as JSON text, or NULL when there is none.
/// The same shape as Android's `CookStateJson`:
///
///     {"active":true,"currentStep":2,"doneSteps":[0,1],
///      "timers":[{"step":1,"totalSeconds":600,"remainingSeconds":600,"endsAt":1700000000000}]}
///
/// `endsAt` is present only while a timer runs. Text that can't be read decodes to an empty
/// `CookProgress`: losing a cook's place is better than failing to open the recipe.
enum CookStateJSON {
    static func encode(_ progress: CookProgress) -> String? {
        if progress.isEmpty { return nil }
        let timers: [[String: Any]] = progress.timers.keys.sorted().map { step in
            let timer = progress.timers[step]!
            var object: [String: Any] = [
                "step": step,
                "totalSeconds": timer.totalSeconds,
                "remainingSeconds": timer.remainingSeconds,
            ]
            if let endsAt = timer.endsAt { object["endsAt"] = endsAt }
            return object
        }
        let root: [String: Any] = [
            "active": progress.active,
            "currentStep": progress.currentStep,
            "doneSteps": progress.doneSteps.sorted(),
            "timers": timers,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    static func decode(_ json: String?) -> CookProgress {
        guard let json, let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return CookProgress() }
        var timers: [Int: SavedTimer] = [:]
        for case let object as [String: Any] in (root["timers"] as? [Any]) ?? [] {
            guard let step = (object["step"] as? NSNumber)?.intValue,
                  let total = (object["totalSeconds"] as? NSNumber)?.intValue,
                  let remaining = (object["remainingSeconds"] as? NSNumber)?.intValue
            else { return CookProgress() }
            timers[step] = SavedTimer(
                totalSeconds: total,
                remainingSeconds: remaining,
                endsAt: (object["endsAt"] as? NSNumber)?.int64Value
            )
        }
        return CookProgress(
            active: (root["active"] as? Bool) ?? false,
            currentStep: (root["currentStep"] as? NSNumber)?.intValue ?? 0,
            doneSteps: Set(((root["doneSteps"] as? [Any]) ?? []).compactMap { ($0 as? NSNumber)?.intValue }),
            timers: timers
        )
    }
}
