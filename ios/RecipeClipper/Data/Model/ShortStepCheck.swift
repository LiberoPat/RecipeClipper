import Foundation

/// Chef mode's gate (#100): whether a short version of a step, written by the on-device model,
/// may be shown in place of the step as written. Pure, and the same as Android's (the
/// differential corpus's `Short` rows pin it).
///
/// The model writes words; code owns every number. A short version passes only if it is shorter
/// than the step, every number in it (fractions, decimal commas and each end of a range, as
/// written) appears in the step, and it states exactly the step's times and temperatures: none
/// changed, none added, none dropped ("350°F (180°C)" may keep either half). Anything else, and
/// any recipe language the app has no words for, shows the step as written.
enum ShortStepCheck {

    /// `short`, tidied, when it may stand for `original`; nil to show `original` as written.
    static func accept(_ original: String, _ short: String?, words: LanguageWords?) -> String? {
        guard let words, let short else { return nil }
        let candidate = tidy(short)
        // UTF-16 lengths, as Kotlin's String.length.
        if candidate.isEmpty || candidate.utf16.count >= tidy(original).utf16.count { return nil }
        if !numbers(candidate).isSubset(of: numbers(original)) { return nil }
        if Set(StepTimers.durations(original, words: words)) != Set(StepTimers.durations(candidate, words: words)) {
            return nil
        }
        let stated = TemperatureConverter.temperatures(original, words: words)
        let kept = Set(TemperatureConverter.temperatures(candidate, words: words).flatMap { $0 })
        if !kept.isSubset(of: Set(stated.flatMap { $0 })) || stated.contains(where: { $0.allSatisfy { !kept.contains($0) } }) {
            return nil
        }
        return candidate
    }

    /// A step this short ("Serve warm.") is left as written, never sent to the model.
    static func worthShortening(_ step: String) -> Bool { tidy(step).utf16.count >= minLength }

    static let minLength = 40

    /// One line: trimmed, whitespace collapsed, a leading bullet and wrapping quotes removed.
    static func tidy(_ text: String) -> String {
        var s = spaces.replace(text.kTrimmed, with: " ")
        s = bullet.replace(s, with: "")
        if s.utf16.count >= 2, let first = s.first, let last = s.last, quotes.contains(first), quotes.contains(last) {
            s = String(s.dropFirst().dropLast()).kTrimmed
        }
        return s
    }

    /// Every number in `text`, as written: "1,5", "1 1/2", "1½", "½"; a range's ends separately.
    static func numbers(_ text: String) -> Set<String> {
        var plain = ""
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0xFF10...0xFF19: plain.unicodeScalars.append(Unicode.Scalar(scalar.value - 0xFF10 + 0x30)!)
            case 0xFF0F, 0x2044: plain.append("/")
            default: plain.unicodeScalars.append(scalar)
            }
        }
        return Set(number.findAll(plain).map { spacedFraction.replace(spaces.replace($0.value, with: " ")) { "\($0[1])\($0[2])" } })
    }

    private static let fractions = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅐⅛⅜⅝⅞⅑⅒"
    private static let number = JRegex(#"\d+(?:[.,]\d+)*(?:\s+\d+/\d+|/\d+|\s*["# + fractions + #"])?|["# + fractions + "]")
    private static let spacedFraction = JRegex(#"(\d) (["# + fractions + "])")
    private static let spaces = JRegex(#"\s+"#)
    private static let bullet = JRegex(#"^[-•*]\s+"#)
    private static let quotes: Set<Character> = ["\"", "'", "“", "”", "„", "«", "»", "「", "」"]
}
