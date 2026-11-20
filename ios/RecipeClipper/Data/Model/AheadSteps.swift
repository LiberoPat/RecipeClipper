import Foundation

/// The step that has to be started ahead of time (#397): its index, and the sentence that says so.
struct AheadStep: Equatable {
    let step: Int
    let quote: String
}

/// Finds the step of a recipe that has to be started the evening before (#397): a marinade, a
/// soak, a thaw, a rise "overnight". Pure; ported line for line from the Kotlin `AheadSteps` (see
/// there for the rules), pinned by the differential corpus's `Ahead` rows. The words are
/// `shared/tables/<language>/ahead.json`, in the recipe's own language (#14): `words` nil is a
/// language with no table, which detects nothing.
enum AheadSteps {

    /// The longest quote: a longer sentence is cut at a word and ends in an ellipsis.
    static let quoteMax = 100

    // How far the hours may sit from their verb: after it, or just before it.
    private static let afterMax = 60
    private static let beforeMax = 40

    // The least hours that count as ahead of time.
    private static let minHours = 2.0

    private final class Rules {
        let always: JRegex
        let verbs: JRegex
        let skip: JRegex
        let upTo: JRegex
        let hours: JRegex
        private let words: LanguageWords

        init(_ words: LanguageWords) {
            self.words = words
            let front = words.spaced ? #"(?<!\p{L})"# : ""
            let back = words.spaced ? #"(?!\p{L})"# : ""
            func alternation(_ key: String) -> String { SharedTables.alternation(words.strings("ahead", key)) }
            func whole(_ key: String) -> JRegex { JRegex(front + alternation(key) + back, ignoreCase: true) }
            always = whole("always")
            verbs = whole("verbs")
            skip = whole("skip")
            // Words right before an amount that make it a limit ("up to 2 hours").
            upTo = JRegex(front + alternation("upTo") + #"\s*\z"#, ignoreCase: true)
            // A number (2 or more) or a count word, a range's first end deciding, then an hours word.
            let lead = words.spaced ? #"(?<![\p{L}\d/.,])"# : #"(?<![\d/.,])"#
            hours = JRegex(
                lead + #"(?:(\d+(?:[.,]\d+)?)|("# + alternation("counts") + #"))"#
                    + #"(?:\s*(?:[-–—]|"# + words.rangeWords + #")\s*\d+(?:[.,]\d+)?)?\s*\+?\s*"#
                    + alternation("hours") + back,
                ignoreCase: true
            )
        }

        func readable(_ text: String) -> String { words.readable(text) }
    }

    private static func rules(_ words: LanguageWords) -> Rules { words.compiled(Rules.self, Rules.init) }

    /// The first step with an ahead-of-time sentence, or nil: none, or `words` nil (a language the
    /// app has no words for).
    static func find(_ steps: [String], words: LanguageWords?) -> AheadStep? {
        guard let words else { return nil }
        let rules = rules(words)
        for (index, step) in steps.enumerated() {
            for sentence in sentences(step) where qualifies(rules.readable(sentence), rules) {
                return AheadStep(step: index, quote: quote(sentence))
            }
        }
        return nil
    }

    private static func qualifies(_ sentence: String, _ rules: Rules) -> Bool {
        let skips = rules.skip.findAll(sentence).map { ($0.start, $0.end - 1) }
        func blocked(_ first: Int, _ last: Int) -> Bool { skips.contains { $0.0 <= last && first <= $0.1 } }

        if rules.always.findAll(sentence).contains(where: { !blocked($0.start, $0.end - 1) }) { return true }

        let hours = hourRanges(sentence, rules)
        return rules.verbs.findAll(sentence).contains { verb in
            let verbFirst = verb.start, verbLast = verb.end - 1
            return hours.contains { wait in
                let after = wait.0 > verbLast && wait.0 - verbLast - 1 <= afterMax
                let before = wait.1 < verbFirst && verbFirst - wait.1 - 1 <= beforeMax
                return (after || before) && !blocked(min(verbFirst, wait.0), max(verbLast, wait.1))
            }
        }
    }

    /// Where `sentence` names two hours or more, leaving out a limit ("up to 12 hours"), as
    /// first and last UTF-16 offsets.
    private static func hourRanges(_ sentence: String, _ rules: Rules) -> [(Int, Int)] {
        rules.hours.findAll(sentence).compactMap { match in
            let figure = match[1]
            let enough = figure.isEmpty ? true : (Double(figure.replacingOccurrences(of: ",", with: ".")) ?? 0.0) >= minHours
            let from = max(0, match.start - 30)
            let before = sentence.u16Substring(from, match.start)
            return enough && !rules.upTo.containsMatch(in: before) ? (match.start, match.end - 1) : nil
        }
    }

    private static func isSpace(_ c: UInt16) -> Bool {
        c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D || c == 0x0B || c == 0x0C || c == 0xA0
    }

    // A lower-case Latin letter: what follows an abbreviation's period ("8 Std. im Kühlschrank"),
    // never a new sentence.
    private static func isLower(_ c: UInt16) -> Bool {
        (c >= 0x61 && c <= 0x7A) || (c >= 0xE0 && c <= 0xFF && c != 0xF7)
    }

    // Whether a sentence ends before `next`: at the end, or at a space and then no lower-case letter.
    private static func endsSentence(_ text: [UInt16], _ next: Int) -> Bool {
        if next == text.count { return true }
        if !isSpace(text[next]) { return false }
        var i = next
        while i < text.count && isSpace(text[i]) { i += 1 }
        return i == text.count || !isLower(text[i])
    }

    /// `text` cut into sentences: after `.`, `!` or `?` followed by a space and not a lower-case
    /// letter (so "1.5 hours" and "8 Std. im" stay whole) or the end, and after `。`, `！` or `？`
    /// wherever they stand.
    static func sentences(_ text: String) -> [String] {
        let units = Array(text.utf16)
        func piece(_ from: Int, _ to: Int) -> String { String(decoding: units[from..<to], as: UTF16.self) }
        var out: [String] = []
        var start = 0
        var i = 0
        while i < units.count {
            let c = units[i]
            let end: Int
            if c == 0x3002 || c == 0xFF01 || c == 0xFF1F {
                end = i + 1
            } else if (c == 0x2E || c == 0x21 || c == 0x3F) && endsSentence(units, i + 1) {
                end = i + 1
            } else {
                end = -1
            }
            if end < 0 {
                i += 1
                continue
            }
            out.append(piece(start, end))
            i = end
            while i < units.count && isSpace(units[i]) { i += 1 }
            start = i
        }
        if start < units.count { out.append(piece(start, units.count)) }
        return out.filter { s in s.utf16.contains { !isSpace($0) } }
    }

    private static let whitespaceRun = JRegex(#"[ \t\n\r\x0B\f ]+"#)

    /// `sentence` on one line, and no longer than `quoteMax`: cut at a word, with an ellipsis.
    static func quote(_ sentence: String) -> String {
        let text = whitespaceRun.split(sentence.kTrimmed).joined(separator: " ")
        if text.u16Count <= quoteMax { return text }
        let cut = text.u16Substring(0, quoteMax)
        let space = (cut as NSString).range(of: " ", options: .backwards).location
        let head = space != NSNotFound && space >= quoteMax / 2 ? cut.u16Substring(0, space) : cut
        var units = Array(head.utf16)
        while let last = units.last, [0x2C, 0x3B, 0x3A, 0x20, 0x2D, 0x2013].contains(last) { units.removeLast() }
        return String(decoding: units, as: UTF16.self) + "…"
    }
}
