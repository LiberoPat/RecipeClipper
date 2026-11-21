import SwiftUI
import XCTest
@testable import RecipeClipper

/// Step photos on the recipe screen (#433; Android's StepPhotosScreenTest and the ViewModel's
/// tests): which steps get a thumbnail and what it reads as, the viewer's page in the ViewModel,
/// and each piece drawing in light and dark, with and without the flag.
@MainActor
final class StepPhotosViewTests: XCTestCase {
    private let one = "https://img.example/1.jpg"
    private let three = "https://img.example/3.jpg"
    private let four = "https://img.example/4.jpg"

    private nonisolated func recipe(photos: [[String]]) -> Recipe {
        var recipe = Recipe(
            name: "Torta di mele", image: nil, ingredients: ["300 g farina"],
            instructions: ["Versa la farina (1).", "Accendi il forno.", "Sbuccia le mele (3) (4).", "Cuoci per 50 minuti."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "8", sourceUrl: "https://www.cookist.it/torta/", id: 1
        )
        recipe.stepPhotos = photos
        return recipe
    }

    private func loaded(_ recipe: Recipe) async -> RecipeViewModel {
        let repository = FakeRecipeRepository()
        repository.openResult = recipe
        let clock = TestClock()
        let vm = RecipeViewModel(
            recipeId: recipe.id, url: nil, repository: repository, preferences: FakeAppPreferences(),
            clock: clock, sleep: clock.sleep
        )
        await settleMain()
        return vm
    }

    // MARK: What each step shows

    func testAStepWithPhotosHasAThumbnailAndOneWithoutHasNone() async throws {
        let vm = await loaded(recipe(photos: [[one], [], [three, four], []]))
        let content = try XCTUnwrap(vm.uiState.content.success)
        guard case .single(let photo) = StepPhotoThumb.of(content.photosOf(0)) else { return XCTFail("step 1 has one photo") }
        XCTAssertEqual(photo.number, 1)
        XCTAssertEqual(StepPhotoThumb.of(content.photosOf(1)), .none)
        guard case .stack(let front, let behind, let more) = StepPhotoThumb.of(content.photosOf(2)) else {
            return XCTFail("step 3 stacks two")
        }
        XCTAssertEqual([front.number, behind.number, more], [3, 4, 1])
        XCTAssertEqual(StepPhotoThumb.of(content.photosOf(3)), .none)
        XCTAssertEqual(StepPhotoThumb.of(content.photosOf(9)), .none)
    }

    func testTheThumbnailReadsAsItsPhotoAndStep() async throws {
        let vm = await loaded(recipe(photos: [[one], [], [three, four], []]))
        let content = try XCTUnwrap(vm.uiState.content.success)
        XCTAssertEqual(StepPhotoThumb.of(content.photosOf(0)).accessibilityLabel, Strings.stepPhotoOpen(1, step: 1))
        XCTAssertEqual(StepPhotoThumb.of(content.photosOf(2)).accessibilityLabel, Strings.stepPhotosOpen(2, step: 3))
        XCTAssertEqual(Strings.stepPhotoOpen(1, step: 1), "Photo 1 of step 1")
        XCTAssertEqual(Strings.stepPhotosOpen(2, step: 3), "2 photos of step 3")
    }

    func testARecipeWithNoStepPhotosHasNoThumbnailAnywhere() async throws {
        let vm = await loaded(recipe(photos: []))
        let content = try XCTUnwrap(vm.uiState.content.success)
        XCTAssertTrue(content.stepPhotos.isEmpty)
        XCTAssertTrue(content.allStepPhotos.isEmpty)
        for index in content.instructions.indices {
            XCTAssertEqual(StepPhotoThumb.of(content.photosOf(index)), .none)
        }
        vm.onStepPhotoOpen(0)
        XCTAssertNil(vm.uiState.stepPhotoViewer)
    }

    // MARK: The viewer

    func testTheViewerOpensOnAPhotoPagesAndCloses() async throws {
        let vm = await loaded(recipe(photos: [[one], [], [three, four], []]))
        XCTAssertNil(vm.uiState.stepPhotoViewer)
        vm.onStepPhotoOpen(1)
        XCTAssertEqual(vm.uiState.stepPhotoViewer, 1)
        vm.onStepPhotoPage(2)
        XCTAssertEqual(vm.uiState.stepPhotoViewer, 2)
        // A page the recipe hasn't got is ignored.
        vm.onStepPhotoPage(3)
        XCTAssertEqual(vm.uiState.stepPhotoViewer, 2)
        vm.onStepPhotoClose()
        XCTAssertNil(vm.uiState.stepPhotoViewer)
        // Paging a closed viewer opens nothing.
        vm.onStepPhotoPage(0)
        XCTAssertNil(vm.uiState.stepPhotoViewer)
    }

    func testTheViewersPagesAreEveryStepPhotoInOrder() async throws {
        let vm = await loaded(recipe(photos: [[one], [], [three, four], []]))
        let content = try XCTUnwrap(vm.uiState.content.success)
        XCTAssertEqual(content.allStepPhotos.map(\.url), [one, three, four])
        XCTAssertEqual(content.allStepPhotos.map(\.step), [0, 2, 2])
        XCTAssertEqual(Strings.stepPhotoPosition(2, of: 3), "Photo 2 of 3")
        XCTAssertEqual(Strings.stepPhotoStep(3), "Step 3")
    }

    // MARK: Drawing

    func testEachPieceDrawsLightAndDark() async throws {
        let vm = await loaded(recipe(photos: [[one], [], [three, four], []]))
        let content = try XCTUnwrap(vm.uiState.content.success)
        for scheme in [ColorScheme.light, .dark] {
            let pieces: [AnyView] = [
                AnyView(StepPhotoThumbnail(photos: content.photosOf(0), onOpen: { _ in })),
                AnyView(StepPhotoThumbnail(photos: content.photosOf(2), size: 56, onOpen: { _ in })),
                AnyView(StepPhotoMiniThumbnail(photos: content.photosOf(2))),
                AnyView(StepPhotoViewer(content: content, position: 1, onPage: { _ in }, onClose: {}).frame(width: 390, height: 800)),
            ]
            for piece in pieces {
                XCTAssertNotNil(ImageRenderer(content: piece.environment(\.colorScheme, scheme)).uiImage, "\(scheme)")
            }
        }
    }

    /// The reading view and cook mode draw with the flag on and off, and with a recipe that has none.
    func testTheReadingViewAndCookModeDrawWithAndWithoutTheFlag() async throws {
        for photos in [[[one], [], [three, four], []], []] {
            let vm = await loaded(recipe(photos: photos))
            let content = try XCTUnwrap(vm.uiState.content.success)
            for flag in [true, false] {
                let reading = ReadingView(content: content, state: vm.uiState, vm: vm)
                    .environment(\.stepPhotos, flag)
                    .frame(width: 390, height: 1200)
                XCTAssertNotNil(ImageRenderer(content: reading).uiImage)
                var cooking = vm.uiState
                cooking.cook.active = true
                cooking.cook.currentStep = 2
                let cook = CookView(content: content, state: cooking, vm: vm)
                    .environment(\.stepPhotos, flag)
                    .frame(width: 390, height: 1200)
                XCTAssertNotNil(ImageRenderer(content: cook).uiImage)
            }
        }
    }

    func testTheFlagIsDeclaredAndOnByDefault() {
        let definition = FlagRegistry.definitions.first { $0.key == "stepPhotos" }
        XCTAssertEqual(definition?.issue, 433)
        XCTAssertEqual(definition?.defaultValue(isDebug: true), true)
        XCTAssertEqual(definition?.defaultValue(isDebug: false), true)
    }
}
