import SwiftUI
import XCTest
@testable import RecipeClipper

/// The no-photo image (#403): which of the monogram and the label is drawn is picked by the size
/// it is given, never the screen; the tints are five and follow the uid. Snapshot-free: the choice
/// is `NoPhotoLayout`, a pure function, and a render only has to succeed.
@MainActor
final class RecipeImagePlaceholderTests: XCTestCase {

    private let lentils = NoPhotoSource(
        uid: "uid-lentils", title: "Grandma's Lentil Soup", language: "en",
        ingredients: ["1 cup red lentils, rinsed", "2 carrots, diced", "1 onion, chopped"]
    )

    func testAThumbnailIsLettering() {
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 64, height: 64), style: .auto), .monogram)
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 44, height: 44), style: .auto), .monogram)
    }

    func testACoverIsTheIngredientLabel() {
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 160, height: 160), style: .auto), .label)
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 84, height: 84), style: .auto), .label)
    }

    func testTheThresholdIsTheShorterSide() {
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 84, height: 64), style: .auto), .monogram)
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 65, height: 65), style: .auto), .label)
    }

    func testABandAndABannerAreTheLabelSetLow() {
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 320, height: 112), style: .auto), .band(tall: false))
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 350, height: 150), style: .auto), .band(tall: true))
    }

    func testTheStyleCanBeAskedFor() {
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 160, height: 160), style: .monogram), .monogram)
        XCTAssertEqual(NoPhotoLayout.of(CGSize(width: 64, height: 64), style: .label), .label)
    }

    func testNamesPerLayout() {
        XCTAssertEqual(NoPhotoLayout.monogram.names, 0)
        XCTAssertEqual(NoPhotoLayout.label.names, 1)
        XCTAssertEqual(NoPhotoLayout.band(tall: true).names, 3)
        let art = NoPhoto.art(lentils, maxLabels: NoPhotoLayout.band(tall: false).names)
        XCTAssertEqual(art.labels, ["red lentils", "carrots", "onion"])
    }

    func testARecipeWithNoIngredientLinesFallsBackToItsLetter() {
        let art = NoPhoto.art(NoPhotoSource(uid: "uid-x", title: "The Best Lasagna", language: "en"), maxLabels: 3)
        XCTAssertEqual(art.labels, [])
        XCTAssertEqual(art.initial, "B")
    }

    func testTheTintIsOneOfFiveAndFollowsTheUid() {
        let a = NoPhoto.art(NoPhotoSource(uid: "same", title: "Lasagna"), maxLabels: 1)
        let b = NoPhoto.art(NoPhotoSource(uid: "same", title: "Renamed"), maxLabels: 1)
        XCTAssertEqual(a.tint, b.tint)
        XCTAssertEqual(Set((1...200).map { NoPhoto.tintIndex("uid-\($0)") }), Set(0..<NoPhoto.tints))
    }

    func testEveryTintHasALightAndAnOnInkVariant() {
        XCTAssertEqual(Tokens.noPhotoLight.count, NoPhoto.tints)
        XCTAssertEqual(Tokens.noPhotoOnInk.count, NoPhoto.tints)
        for index in 0..<NoPhoto.tints {
            XCTAssertNotEqual(Tokens.noPhotoLight[index].ground, Tokens.noPhotoOnInk[index].ground)
            XCTAssertNotEqual(Tokens.noPhotoLight[index].ink, Tokens.noPhotoOnInk[index].ink)
        }
    }

    /// Each layout draws, light and dark, with the flag on; with it off the slot is the hairline.
    func testEachLayoutRenders() {
        for scheme in [ColorScheme.light, .dark] {
            for size in [CGSize(width: 64, height: 64), CGSize(width: 160, height: 160), CGSize(width: 320, height: 112)] {
                let view = RecipeImagePlaceholder(recipe: lentils)
                    .frame(width: size.width, height: size.height)
                    .environment(\.noPhotoImage, true)
                    .environment(\.colorScheme, scheme)
                let renderer = ImageRenderer(content: view)
                XCTAssertNotNil(renderer.uiImage, "\(size) \(scheme)")
            }
        }
        let off = ImageRenderer(content: RecipeImagePlaceholder(recipe: lentils).frame(width: 64, height: 64))
        XCTAssertNotNil(off.uiImage)
    }
}
