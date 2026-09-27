import Foundation

extension PageRecipeCheck {

    /// `source` folded for comparison, in UTF-16 units, with where each folded unit came from:
    /// `starts`/`ends` are its code point's range in `source`, and `lineBreak` marks a space that
    /// stands for whitespace holding a line break. Android's `PageRecipeCheck.Folded`.
    struct Folded {
        let text: [UInt16]
        let lineBreak: [Bool]
        private let starts: [Int]
        private let ends: [Int]
        private let source: [UInt16]

        init(_ s: String) {
            source = Array(s.utf16)
            var out: [UInt16] = [], st: [Int] = [], en: [Int] = [], br: [Bool] = []
            var i = 0
            for scalar in s.unicodeScalars {
                let next = i + UTF16.width(scalar)
                let mapped = String(scalar).precomposedStringWithCompatibilityMapping.lowercased()
                for c in mapped.utf16 {
                    let newline = c == 0x0A || c == 0x0D
                    if isWhitespace(c) {
                        if out.isEmpty { continue }
                        if out.last == space {
                            if newline { br[br.count - 1] = true }
                            en[en.count - 1] = next
                            continue
                        }
                        out.append(space); br.append(newline)
                    } else {
                        out.append(punctuation[c] ?? c); br.append(false)
                    }
                    st.append(i); en.append(next)
                }
                i = next
            }
            while out.last == space {
                out.removeLast(); st.removeLast(); en.removeLast(); br.removeLast()
            }
            text = out; lineBreak = br; starts = st; ends = en
        }

        /// The source text behind folded `from` until `to`: trimmed as Kotlin trims, its runs of
        /// `\s` (Java's: space, tab, line breaks, form feed) as one space.
        func original(_ from: Int, _ to: Int) -> String {
            var units = Array(source[starts[from]..<ends[to - 1]])
            while let f = units.first, isWhitespace(f) { units.removeFirst() }
            while let l = units.last, isWhitespace(l) { units.removeLast() }
            var out: [UInt16] = []
            var inRun = false
            for u in units {
                if javaSpace.contains(u) {
                    if !inRun { out.append(space) }
                    inRun = true
                } else {
                    out.append(u)
                    inRun = false
                }
            }
            return String(decoding: out, as: UTF16.self)
        }
    }

    static let space: UInt16 = 0x20
    static let slash: UInt16 = 0x2F
    static let hyphen: UInt16 = 0x2D
    static let dot: UInt16 = 0x2E
    static let comma: UInt16 = 0x2C
    private static let javaSpace: Set<UInt16> = [0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D]

    private static let punctuation: [UInt16: UInt16] = {
        var map: [UInt16: UInt16] = [:]
        for c in "‘’‚‛′`´".utf16 { map[c] = 0x27 }
        for c in "“”„‟″«»".utf16 { map[c] = 0x22 }
        for c in "‐‑‒–—―−".utf16 { map[c] = hyphen }
        for c in "⁄∕".utf16 { map[c] = slash }
        map[0xD7] = 0x78 // × as x
        return map
    }()

    private static func category(_ c: UInt16) -> Unicode.GeneralCategory? {
        Unicode.Scalar(c)?.properties.generalCategory
    }

    /// Kotlin's `Char.isLetter()`: Lu, Ll, Lt, Lm, Lo (never a lone surrogate).
    static func isLetter(_ c: UInt16) -> Bool {
        switch category(c) {
        case .uppercaseLetter?, .lowercaseLetter?, .titlecaseLetter?, .modifierLetter?, .otherLetter?: return true
        default: return false
        }
    }

    /// Kotlin's `Char.isDigit()`: Nd.
    static func isDigit(_ c: UInt16) -> Bool { category(c) == .decimalNumber }

    static func isLetterOrDigit(_ c: UInt16) -> Bool { isLetter(c) || isDigit(c) }

    /// Kotlin's `Char.isWhitespace()`: Zs, Zl, Zp, U+0009–U+000D and U+001C–U+001F.
    static func isWhitespace(_ c: UInt16) -> Bool {
        if (0x09...0x0D).contains(c) || (0x1C...0x1F).contains(c) { return true }
        switch category(c) {
        case .spaceSeparator?, .lineSeparator?, .paragraphSeparator?: return true
        default: return false
        }
    }
}
