import SwiftUI
import XCTest
@testable import RecipeClipper

/// Cook mode is forced onto ink with `.environment(\.colorScheme, .dark)`, which only works
/// if the palette's dynamic colours resolve against SwiftUI's environment rather than the
/// window's trait collection. Rendered, not assumed.
@MainActor
final class ThemeTests: XCTestCase {

    private func pixel(_ color: Color, scheme: ColorScheme) throws -> (r: Int, g: Int, b: Int) {
        let renderer = ImageRenderer(content: Rectangle().fill(color).frame(width: 4, height: 4)
            .environment(\.colorScheme, scheme))
        renderer.scale = 1
        let image = try XCTUnwrap(renderer.cgImage)
        var bytes = [UInt8](repeating: 0, count: 4)
        let context = try XCTUnwrap(CGContext(
            data: &bytes, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.draw(image, in: CGRect(x: -1, y: -1, width: 4, height: 4))
        return (Int(bytes[0]), Int(bytes[1]), Int(bytes[2]))
    }

    private func assertColor(_ actual: (r: Int, g: Int, b: Int), _ hex: Int, file: StaticString = #filePath, line: UInt = #line) {
        let expected = ((hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF)
        XCTAssertLessThanOrEqual(abs(actual.r - expected.0) + abs(actual.g - expected.1) + abs(actual.b - expected.2), 6,
                                 "got \(actual), expected \(String(hex, radix: 16))", file: file, line: line)
    }

    func testTheGroundFollowsAForcedColorScheme() throws {
        assertColor(try pixel(Palette.background, scheme: .light), 0xFBF9F6)
        assertColor(try pixel(Palette.background, scheme: .dark), 0x1C1917)
    }

    func testTextAccentUsesTheOnInkVariantInDark() throws {
        assertColor(try pixel(Palette.accentText, scheme: .light), 0xBF4A2B)
        assertColor(try pixel(Palette.accentText, scheme: .dark), 0xE2735A)
        assertColor(try pixel(Palette.muted, scheme: .dark), 0xA39A90)
    }

    func testTheBundledFontsAreRegistered() {
        XCTAssertEqual(AppFont.fraunces(20, weight: 600).familyName, "Fraunces")
        XCTAssertEqual(AppFont.karla(17, weight: 400).familyName, "Karla")
    }

    /// The bundled fonts are variable; a weight that doesn't reach the `wght` axis would render
    /// every style at the file's default weight. Bold text sets wider than regular.
    func testTheWeightAxisActuallyVaries() {
        func width(_ font: UIFont) -> CGFloat {
            ("Recipe Clipper guacamole" as NSString).size(withAttributes: [.font: font]).width
        }
        XCTAssertGreaterThan(width(AppFont.karla(17, weight: 700)), width(AppFont.karla(17, weight: 400)) + 1)
        XCTAssertGreaterThan(width(AppFont.fraunces(30, weight: 700)), width(AppFont.fraunces(30, weight: 300)) + 1)
    }

    /// The UIKit tint (alerts, menus, cursors) is the same paprika as `accentText`, never blue.
    func testTheUIKitTintIsPaprikaInBothSchemes() {
        func rgb(_ style: UIUserInterfaceStyle) -> Int {
            let c = Palette.uiAccentText.resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            c.getRed(&r, green: &g, blue: &b, alpha: &a)
            return Int((r * 255).rounded()) << 16 | Int((g * 255).rounded()) << 8 | Int((b * 255).rounded())
        }
        XCTAssertEqual(rgb(.light), 0xBF4A2B)
        XCTAssertEqual(rgb(.dark), 0xE2735A)
    }

    /// Every token against Theme.kt, in both schemes.
    func testEveryTokenMatchesThemeKt() throws {
        let expected: [(Color, Int, Int)] = [
            (Palette.background, 0xFBF9F6, 0x1C1917),
            (Palette.onBackground, 0x1C1917, 0xFBF9F6),
            (Palette.muted, 0x6B6259, 0xA39A90),
            (Palette.hairline, 0xE7E1D9, 0x3A342F),
            (Palette.accentText, 0xBF4A2B, 0xE2735A),
            (Palette.primary, 0xBF4A2B, 0xBF4A2B),
            (Palette.error, 0xBF4A2B, 0xE2735A),
            (Palette.surfaceContainer, 0xF3EFE9, 0x26211E),
            (Palette.inverseSurface, 0x1C1917, 0xFBF9F6),
            (Palette.inverseOnSurface, 0xFBF9F6, 0x1C1917),
            (Palette.inversePrimary, 0xE2735A, 0xBF4A2B),
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
            (Typography.headlineMedium, 30, 36),
            (Typography.headlineSmall, 26, 32),
            (Typography.titleLarge, 22, 28),
            (Typography.titleMedium, 19, 26),
            (Typography.titleSmall, 15, 20),
            (Typography.bodyLarge, 17, 26),
            (Typography.bodyLargeBold, 17, 26),
            (Typography.bodyMedium, 15, 22),
            (Typography.bodySmall, 13, 18),
            (Typography.labelLarge, 16, 20),
            (Typography.labelMedium, 13, 16),
            (Typography.labelSmall, 12, 16),
            (Typography.cookStep, 21, 30),
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

    /// The scaled font is still the bundled variable font, at a weight that still varies.
    func testScaledFontsKeepTheirFamilyAndWeight() {
        let ax5 = UIContentSizeCategory.accessibilityExtraExtraExtraLarge
        XCTAssertEqual(Typography.headlineMedium.uiFont(for: ax5).familyName, "Fraunces")
        XCTAssertEqual(Typography.bodyLarge.uiFont(for: ax5).familyName, "Karla")
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
