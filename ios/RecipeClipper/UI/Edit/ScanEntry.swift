import SwiftUI
import UIKit

/// Where a scan's pages come from (#226), as chosen from a menu; `.noCamera` when the camera
/// was chosen on a device without one (the simulator), which says so.
enum ScanSource: Hashable {
    case camera, library, noCamera
}

/// "Scan a recipe"'s two choices (#226), as menu buttons: #116's camera or library.
struct ScanChoices: View {
    @Binding var source: ScanSource?

    var body: some View {
        Button(Strings.takePhoto) {
            source = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .noCamera
        }
        Button(Strings.choosePhotos) { source = .library }
    }
}

extension View {
    /// Presents the camera or the photo library (#116's pickers: no library access asked for;
    /// the camera asks the first time) for a scan (#226), and hands the pictures, in the order
    /// picked, to `onScan` once the picker has gone.
    func scanSources(_ source: Binding<ScanSource?>, onScan: @escaping ([Data]) -> Void) -> some View {
        modifier(ScanSourcesModifier(source: source, onScan: onScan))
    }
}

private struct ScanSourcesModifier: ViewModifier {
    @Binding var source: ScanSource?
    let onScan: ([Data]) -> Void
    /// What the library or the camera handed back, passed on once its screen has gone.
    @State private var picked: Task<[Data], Never>?

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: showing(.library), onDismiss: handOver) {
                LibraryPicker(selectionLimit: ScanPages.maxPages, ordered: true) { results in
                    picked = Task { await LibraryPicker.pictures(results) }
                }
                .ignoresSafeArea()
            }
            .fullScreenCover(isPresented: showing(.camera), onDismiss: handOver) {
                CameraPicker { data in picked = Task { [data] } }.ignoresSafeArea()
            }
            .alert(Strings.cameraUnavailable, isPresented: showing(.noCamera)) {}
    }

    private func showing(_ wanted: ScanSource) -> Binding<Bool> {
        Binding(get: { source == wanted }, set: { if !$0, source == wanted { source = nil } })
    }

    private func handOver() {
        guard let picked else { return }
        self.picked = nil
        Task {
            let pictures = await picked.value
            if !pictures.isEmpty { onScan(pictures) }
        }
    }
}

/// Home's "Scan a recipe" (#226), beside "+ New recipe": a menu of the camera or the library.
struct ScanRecipeButton: View {
    let onScan: ([Data]) -> Void
    @State private var source: ScanSource?

    var body: some View {
        Menu {
            ScanChoices(source: $source)
        } label: {
            Text(Strings.scanRecipe)
        }
        .buttonStyle(TextActionStyle())
        .accessibilityIdentifier("home.scanRecipe")
        .scanSources($source, onScan: onScan)
    }
}
