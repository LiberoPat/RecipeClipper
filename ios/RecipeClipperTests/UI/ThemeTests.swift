import SwiftUI
import XCTest
@testable import RecipeClipper

/// Cook mode is forced onto ink with `.environment(\.colorScheme, .dark)`, which only works
/// if the palette's dynamic colours resolve against SwiftUI's environment rather than the
/// window's trait collection. Rendered, not assumed.
@MainActor
final class ThemeTests: XCTestCase {

    /// On a slow first run, `ImageRenderer.cgImage` can hand back an image before the render
    /// pipeline has actually composited anything into it — every pixel reads black regardless
    /// of the fill colour, for ~10–14 s while the pipeline warms up (#77). None of the tokens
    /// this file checks against are pure black (the closest, the dark ground, is 0x0E1310), so a still-black
    /// sample is never a real answer: retry until a real frame lands, or give up after a bound
    /// generous enough to cover that warm-up on a slow machine. This replaces a fixed delay with
    /// waiting for the actual condition, and never masks a genuine black-pixel regression, since
    /// `assertColor` still runs — and can still fail — on whatever `pixel` last saw.
    private func pixel(_ color: Color, scheme: ColorScheme) throws -> (r: Int, g: Int, b: Int) {
        let deadline = Date().addingTimeInterval(30)
        var last = (r: 0, g: 0, b: 0)
        repeat {
            let renderer = ImageRenderer(content: Rectangle().fill(color).frame(width: 4, height: 4)
                .environment(\.colorScheme, scheme))
            renderer.scale = 1
            if let image = renderer.cgImage {
                var bytes = [UInt8](repeating: 0, count: 4)
                if let context = CGContext(
                    data: &bytes, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                ) {
                    context.draw(image, in: CGRect(x: -1, y: -1, width: 4, height: 4))
                    last = (Int(bytes[0]), Int(bytes[1]), Int(bytes[2]))
                    if last != (0, 0, 0) { return last }
                }
            }
            // Pump the run loop rather than sleeping the thread, so any pending render
            // completion has a chance to actually finish before the next attempt.
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        } while Date() < deadline
        return last
    }

    private func assertColor(_ actual: (r: Int, g: Int, b: Int), _ hex: Int, file: StaticString = #filePath, line: UInt = #line) {
        let expected = ((hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF)
        XCTAssertLessThanOrEqual(abs(actual.r - expected.0) + abs(actual.g - expected.1) + abs(actual.b - expected.2), 6,
                                 "got \(actual), expected \(String(hex, radix: 16))", file: file, line: line)
    }

    func testTheGroundFollowsAForcedColorScheme() throws {
        assertColor(try pixel(Palette.background, scheme: .light), 0xFFFFFF)
        assertColor(try pixel(Palette.background, scheme: .dark), 0x0E1310)
    }

    func testTextAccentUsesTheOnInkVariantInDark() throws {
        assertColor(try pixel(Palette.accentText, scheme: .light), 0x25683E)
        assertColor(try pixel(Palette.accentText, scheme: .dark), 0x7ED39C)
        assertColor(try pixel(Palette.muted, scheme: .dark), 0x9BA89F)
    }

    /// Every bundled static weight registers under its PostScript name (`UIAppFonts`), and the
    /// theme's fonts are those, never a system fallback.
    func testTheBundledFontsAreRegistered() throws {
        for (display, weight) in [(true, 600.0), (true, 700), (true, 800), (false, 400), (false, 500), (false, 600), (false, 700)] {
            let name = AppFont.fontName(display: display, weight: weight)
            let font = try XCTUnwrap(UIFont(name: name, size: 17), "\(name) is not registered")
            XCTAssertEqual(font.fontName, name)
        }
        XCTAssertEqual(AppFont.fontName(display: true, weight: 700), "BricolageGrotesque-24ptBold")
        XCTAssertEqual(AppFont.fontName(display: false, weight: 400), "InstrumentSans-Regular")
        XCTAssertEqual(AppFont.bricolage(20, weight: 700).fontName, "BricolageGrotesque-24ptBold")
        XCTAssertEqual(AppFont.instrumentSans(17, weight: 400).fontName, "InstrumentSans-Regular")
    }

    /// A weight picks the nearest bundled one, so each role really changes weight: bold text sets
    /// wider than regular.
    func testTheWeightActuallyVaries() {
        func width(_ font: UIFont) -> CGFloat {
            ("Recipe Clipper guacamole" as NSString).size(withAttributes: [.font: font]).width
        }
        XCTAssertEqual(AppFont.fontName(display: true, weight: 720), "BricolageGrotesque-24ptBold")
        XCTAssertEqual(AppFont.fontName(display: false, weight: 650), "InstrumentSans-SemiBold")
        XCTAssertGreaterThan(width(AppFont.instrumentSans(17, weight: 700)), width(AppFont.instrumentSans(17, weight: 400)) + 1)
        XCTAssertGreaterThan(width(AppFont.bricolage(30, weight: 800)), width(AppFont.bricolage(30, weight: 600)) + 1)
    }

    /// The UIKit tint (alerts, menus, cursors) is the same basil as `accentText`, never blue.
    func testTheUIKitTintIsBasilInBothSchemes() {
        func rgb(_ style: UIUserInterfaceStyle) -> Int {
            let c = Palette.uiAccentText.resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            c.getRed(&r, green: &g, blue: &b, alpha: &a)
            return Int((r * 255).rounded()) << 16 | Int((g * 255).rounded()) << 8 | Int((b * 255).rounded())
        }
        XCTAssertEqual(rgb(.light), 0x25683E)
        XCTAssertEqual(rgb(.dark), 0x7ED39C)
    }

    /// Direction A, "Market" (#455): the key tokens' values in light and dark, resolved exactly
    /// from the dynamic colours, so a stray revert of any of them fails here.
    func testMarketsKeyTokensInLightAndDark() {
        func rgb(_ color: Color, _ style: UIUserInterfaceStyle) -> Int {
            let c = UIColor(color).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            c.getRed(&r, green: &g, blue: &b, alpha: &a)
            return Int((r * 255).rounded()) << 16 | Int((g * 255).rounded()) << 8 | Int((b * 255).rounded())
        }
        let expected: [(String, Color, Int, Int)] = [
            ("background", Palette.background, 0xFFFFFF, 0x0E1310),
            ("onBackground", Palette.onBackground, 0x14201A, 0xEDF3EE),
            ("muted", Palette.muted, 0x56645B, 0x9BA89F),
            ("hairline", Palette.hairline, 0xE1E7E2, 0x28332C),
            ("primary", Palette.primary, 0x2C7A4A, 0x5DBE80),
            ("onPrimary", Palette.onPrimary, 0xFFFFFF, 0x06170C),
            ("accentText", Palette.accentText, 0x25683E, 0x7ED39C),
            ("surfaceContainer", Palette.surfaceContainer, 0xF2F6F2, 0x17201A),
            ("surfaceContainerHigh", Palette.surfaceContainerHigh, 0xE6EDE7, 0x202B24),
            ("accentTone", Palette.accentTone, 0xE1F0E5, 0x1B3324),
            ("onAccentTone", Palette.onAccentTone, 0x1C5232, 0xA9E3BC),
            ("outline", Palette.outline, 0x9AA79E, 0x6B7A70),
        ]
        for (name, color, light, dark) in expected {
            XCTAssertEqual(String(rgb(color, .light), radix: 16), String(light, radix: 16), "\(name) light")
            XCTAssertEqual(String(rgb(color, .dark), radix: 16), String(dark, radix: 16), "\(name) dark")
        }
        let tints: [(Int, Int, Int, Int)] = [
            (0xDDEFE2, 0x1C5232, 0x1B3324, 0xA9E3BC),
            (0xFBF0C2, 0x5F4A00, 0x3A3210, 0xF6DE7A),
            (0xFADBD2, 0x842913, 0x3D1D15, 0xF7B8A6),
            (0xDCEAF5, 0x1D4A6E, 0x162A3A, 0xA9CDEA),
            (0xEFE6D6, 0x5A4729, 0x2F2A20, 0xE3D3B4),
        ]
        for (i, (ground, ink, groundOnInk, inkOnInk)) in tints.enumerated() {
            let tint = Palette.noPhotoTint(i)
            XCTAssertEqual(rgb(tint.ground, .light), ground, "tint \(i) ground")
            XCTAssertEqual(rgb(tint.ink, .light), ink, "tint \(i) ink")
            XCTAssertEqual(rgb(tint.ground, .dark), groundOnInk, "tint \(i) ground on ink")
            XCTAssertEqual(rgb(tint.ink, .dark), inkOnInk, "tint \(i) ink on ink")
        }
    }

    /// Every token against Theme.kt, in both schemes.
    func testEveryTokenMatchesThemeKt() throws {
        let expected: [(Color, Int, Int)] = [
            (Palette.background, 0xFFFFFF, 0x0E1310),
            (Palette.onBackground, 0x14201A, 0xEDF3EE),
            (Palette.muted, 0x56645B, 0x9BA89F),
            (Palette.hairline, 0xE1E7E2, 0x28332C),
            (Palette.accentText, 0x25683E, 0x7ED39C),
            (Palette.primary, 0x2C7A4A, 0x5DBE80),
            (Palette.error, 0xB2371F, 0xF4A28C),
            (Palette.surfaceContainer, 0xF2F6F2, 0x17201A),
            (Palette.inverseSurface, 0x14201A, 0xEDF3EE),
            (Palette.inverseOnSurface, 0xFFFFFF, 0x0E1310),
            (Palette.inversePrimary, 0x7ED39C, 0x25683E),
        ]
        for (color, light, dark) in expected {
            assertColor(try pixel(color, scheme: .light), light)
            assertColor(try pixel(color, scheme: .dark), dark)
        }
    }

    // MARK: - Dynamic Type

    /// At the default (Large) size every role is exactly Theme.kt's size and line height: the
    /// design at the default size must not move.
    func testDefaultSizesMatchThemeKt() {
        let expected: [(TextStyle, CGFloat, CGFloat)] = [
            (Typography.headlineMedium, 32, 38),
            (Typography.headlineSmall, 27, 32),
            (Typography.titleLarge, 22, 27),
            (Typography.titleMedium, 19, 24),
            (Typography.titleSmall, 16, 21),
            (Typography.bodyLarge, 17, 25),
            (Typography.bodyLargeBold, 17, 25),
            (Typography.bodyMedium, 15, 21),
            (Typography.bodySmall, 13, 18),
            (Typography.labelLarge, 15, 20),
            (Typography.labelMedium, 13, 16),
            (Typography.labelSmall, 12, 16),
            (Typography.cookStep, 21, 29),
            (Typography.stepNumber, 18, 24),
            (Typography.timerClock, 30, 36),
        ]
        XCTAssertEqual(expected.count, Typography.all.count, "a role without a pinned default size")
        for (style, size, lineHeight) in expected {
            XCTAssertEqual(style.uiFont(for: .large).pointSize, size, accuracy: 0.001)
            XCTAssertEqual(style.lineHeight(for: .large), lineHeight, accuracy: 0.001)
            XCTAssertEqual(style.uiFont.pointSize, size, accuracy: 0.001)
        }
    }

    /// Every role grows with the content size category, strictly, from the smallest setting
    /// to AX5 — parity with Android's `sp`, which follows the system font size.
    func testEveryRoleScalesWithContentSize() {
        let categories: [UIContentSizeCategory] = [
            .extraSmall, .large, .extraExtraExtraLarge, .accessibilityMedium, .accessibilityExtraExtraExtraLarge
        ]
        for style in Typography.all {
            let sizes = categories.map { style.uiFont(for: $0).pointSize }
            for (smaller, larger) in zip(sizes, sizes.dropFirst()) {
                XCTAssertLessThan(smaller, larger, "\(style.baseSize)pt role: \(sizes)")
            }
            // Every role at least doubles by AX5: genuinely big, not creeping up a point or two.
            XCTAssertGreaterThanOrEqual(sizes.last!, style.baseSize * 2, "\(style.baseSize)pt role at AX5: \(sizes.last!)")
            // The line height scales in step with the font, so the proportions Theme.kt set
            // hold at every size (and lineSpacing, clamped at zero, never overlaps lines).
            let ax5 = UIContentSizeCategory.accessibilityExtraExtraExtraLarge
            XCTAssertEqual(style.lineHeight(for: ax5) / style.baseLineHeight,
                           style.uiFont(for: ax5).pointSize / style.baseSize, accuracy: 0.05)
            XCTAssertGreaterThanOrEqual(style.lineSpacing(for: ax5), 0)
        }
    }

    /// The scaled font is still the bundled font, at a weight that still varies.
    func testScaledFontsKeepTheirFamilyAndWeight() {
        let ax5 = UIContentSizeCategory.accessibilityExtraExtraExtraLarge
        XCTAssertEqual(Typography.headlineMedium.uiFont(for: ax5).fontName, "BricolageGrotesque-24ptBold")
        XCTAssertEqual(Typography.bodyLarge.uiFont(for: ax5).fontName, "InstrumentSans-Regular")
        func width(_ font: UIFont) -> CGFloat {
            ("Recipe Clipper guacamole" as NSString).size(withAttributes: [.font: font]).width
        }
        let regular = Typography.bodyLarge.uiFont(for: ax5)
        let bold = Typography.bodyLargeBold.uiFont(for: ax5)
        XCTAssertEqual(regular.pointSize, bold.pointSize, accuracy: 0.001)
        XCTAssertGreaterThan(width(bold), width(regular) + 1)
    }

    /// `.textStyle` resolves against the environment's Dynamic Type size, which is what makes
    /// it follow the setting live (and honour a `.dynamicTypeSize(...)` cap). Rendered, not
    /// assumed: the same text is taller in an AX5 environment than at Large.
    func testTheModifierFollowsTheEnvironmentsDynamicTypeSize() throws {
        func height(_ size: DynamicTypeSize) throws -> CGFloat {
            let renderer = ImageRenderer(content: Text("Guacamole").textStyle(Typography.bodyLarge).fixedSize()
                .environment(\.dynamicTypeSize, size))
            renderer.scale = 1
            return CGFloat(try XCTUnwrap(renderer.cgImage).height)
        }
        let large = try height(.large)
        let ax5 = try height(.accessibility5)
        XCTAssertEqual(large, ceil(Typography.bodyLarge.uiFont.lineHeight), accuracy: 1)
        XCTAssertGreaterThan(ax5, large * 2)
    }
}
