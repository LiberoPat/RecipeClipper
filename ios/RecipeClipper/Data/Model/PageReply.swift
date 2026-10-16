import Foundation

/// One answer from the on-device model reading a page (#103), as the extractor saw it (#273).
/// Android's `PageReply`.
enum PageReply: Equatable {
    /// A reply that read as a pick (possibly an empty one: the page holds no recipe).
    case picked(PageSelection)
    /// The model ran away: a reply cut off at its limit, or one `RunawayReply` stopped. Worth
    /// one more try (`PageReplyRetry`).
    case unreadable
    /// The model couldn't answer at all: a guardrail, a language it won't read, rate limited,
    /// busy. Never retried: it isn't the reply's fault.
    case failed
}

/// One retry for an unreadable reply (#273): the small models sometimes pad a field (often a
/// time) or repeat one line until the reply's limit, and the page then shows no recipe. The retry
/// changes the prompt, so a deterministic model doesn't loop the same way again: `reminder` after
/// the instructions, and a reply cap of `retryReplyTokens`, so a second runaway costs less. Never
/// more than one retry, and never after `.failed`. A retry only ever runs for a page that would
/// otherwise show no recipe, so its cap can't cost one that shows today. Android's `PageReplyRetry`.
enum PageReplyRetry {
    /// The reply cap for the retry, in tokens.
    static let retryReplyTokens = 2_048

    /// Appended to the instructions for the retry.
    static let reminder = "Keep the yield and each time short, as the page writes them (like \"30 minutes\" "
        + "or \"4 servings\"), or null: never explain them. Copy each line once, then stop."

    /// The recipe `ask` picks: asked once (`retry` false), and once more (`retry` true) only if
    /// that reply was `.unreadable`. Nil when it found none or couldn't answer.
    static func pick(_ ask: (_ retry: Bool) async -> PageReply) async -> PageSelection? {
        switch await ask(false) {
        case .picked(let selection): return selection
        case .failed: return nil
        case .unreadable:
            if case .picked(let selection) = await ask(true) { return selection }
            return nil
        }
    }

    /// The same, for a caller that asks synchronously (the evaluation harness, tools/eval).
    static func pick(_ ask: (_ retry: Bool) -> PageReply) -> PageSelection? {
        switch ask(false) {
        case .picked(let selection): return selection
        case .failed: return nil
        case .unreadable:
            if case .picked(let selection) = ask(true) { return selection }
            return nil
        }
    }
}

/// Spots a reply running away (#273) while it is still being written, so the extractor can stop
/// it early: a field far longer than any page writes (a name over `maxName` characters, a yield
/// or time over `maxShort`, a line over `maxLine`), or the same ingredient or step `maxRepeats`
/// times running. The limits are generous on purpose: a stop throws the reply away. Lengths count
/// UTF-16 units, as Kotlin's do. Android's `RunawayReply`, pinned by the differential corpus's
/// `Runaway` rows.
enum RunawayReply {
    static let maxName = 200
    static let maxShort = 300
    static let maxLine = 2_000
    static let maxRepeats = 4

    /// True when `pick` (perhaps a reply still being written) has run away.
    static func isRunaway(_ pick: PageSelection) -> Bool {
        (pick.name?.utf16.count ?? 0) > maxName
            || [pick.yield, pick.prepTime, pick.cookTime, pick.totalTime].contains { ($0?.utf16.count ?? 0) > maxShort }
            || repeats(pick.ingredients) || repeats(pick.steps)
    }

    /// True when the JSON reply `text` (perhaps cut off, or still being written) has run away.
    static func isRunaway(_ text: String) -> Bool { isRunaway(scan(text)) }

    private static func repeats(_ lines: [String]) -> Bool {
        var run = 0
        for i in lines.indices {
            if lines[i].utf16.count > maxLine { return true }
            run = i > 0 && lines[i].utf16.elementsEqual(lines[i - 1].utf16) ? run + 1 : 1
            if run >= maxRepeats { return true }
        }
        return false
    }

    private static let shortFields: Set<String> = ["name", "yield", "prepTime", "cookTime", "totalTime"]
    private static let listFields: Set<String> = ["ingredients", "steps"]

    /// The fields of a JSON reply as far as it goes, leniently: the top-level object's string values
    /// for `name`, `yield` and the times, and the strings in its `ingredients` and `steps` arrays,
    /// the last one perhaps unfinished. Anything else (a code fence, other keys, nested objects,
    /// numbers) is skipped. For spotting a runaway only, never for reading a pick.
    static func scan(_ text: String) -> PageSelection {
        let units = Array(text.utf16)
        func unit(_ c: Character) -> UInt16 { c.utf16.first! }
        let (quote, backslash) = (unit("\""), unit("\\"))
        var fields: [String: String] = [:]
        var lists: [String: [String]] = [:]
        // The open containers: true for an object, false for an array.
        var stack: [Bool] = []
        var expectKey = false
        var key: String?
        var i = 0
        while i < units.count {
            switch units[i] {
            case unit("{"): stack.append(true); expectKey = true
            case unit("["): stack.append(false)
            case unit("}"), unit("]"): if !stack.isEmpty { stack.removeLast() }; expectKey = false
            case unit(","): expectKey = stack.last == true
            case unit(":"): expectKey = false
            case quote:
                var value: [UInt16] = []
                i += 1
                while i < units.count && units[i] != quote {
                    if units[i] == backslash && i + 1 < units.count {
                        i += 1
                        switch units[i] {
                        case unit("n"): value.append(unit("\n"))
                        case unit("t"): value.append(unit("\t"))
                        case unit("r"): value.append(unit("\r"))
                        case unit("b"), unit("f"): break
                        case unit("u"):
                            let hex = units[(i + 1)..<min(i + 5, units.count)]
                            let digits = hex.compactMap { h -> UInt16? in
                                switch h {
                                case 48...57: return h - 48
                                case 97...102: return h - 87
                                case 65...70: return h - 55
                                default: return nil
                                }
                            }
                            if hex.count == 4 && digits.count == 4 { value.append(digits.reduce(0) { $0 * 16 + $1 }) }
                            i += hex.count
                        default: value.append(units[i])
                        }
                    } else if units[i] != backslash {
                        value.append(units[i])
                    }
                    i += 1
                }
                let s = String(decoding: value, as: UTF16.self)
                if stack.count == 1 && stack[0] && expectKey {
                    key = s
                } else if stack.count == 1 && stack[0], let k = key, shortFields.contains(k) {
                    fields[k] = s
                } else if stack.count == 2 && stack[0] && !stack[1], let k = key, listFields.contains(k) {
                    lists[k, default: []].append(s)
                }
            default: break
            }
            i += 1
        }
        return PageSelection(
            name: fields["name"], ingredients: lists["ingredients"] ?? [], steps: lists["steps"] ?? [],
            yield: fields["yield"], prepTime: fields["prepTime"], cookTime: fields["cookTime"],
            totalTime: fields["totalTime"]
        )
    }
}
