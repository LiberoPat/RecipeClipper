import SwiftUI
import UIKit

/// A drawn recipe for the print dialog: its PDF and the job's name.
struct PrintDocument: Equatable {
    let id = UUID()
    let data: Data
    let name: String
}

/// The platform half of printing (#318): draws `PrintViewModel`'s job into a PDF, then opens the
/// system print dialog on a recipe (whose share button also saves the PDF) or the share sheet on
/// a cookbook, as Send as file does; an alert when it couldn't be made. Android's `PrintEffect`.
struct PrintEffect: ViewModifier {
    let vm: PrintViewModel?
    @State private var shareURL: URL?
    @State private var document: PrintDocument?

    // The same modifiers whether or not there is a ViewModel yet, so the content keeps its identity.
    func body(content: Content) -> some View {
        content
            .background(ShareSheetAnchor(item: shareURL) { shareURL = nil })
            .background(PrintSheetAnchor(document: document) { document = nil })
            .onChange(of: vm?.uiState.job) { _, job in
                guard let vm, let job else { return }
                switch job {
                case .recipe(let title, let blocks):
                    let pdf = PrintPdf().recipe(blocks, photos: PrintPdf.cachedPhotos(blocks))
                    document = PrintDocument(data: pdf, name: title)
                    vm.onDone()
                case .cookbook(let title, let contents, let recipes):
                    let photos = PrintPdf.cachedPhotos(recipes.flatMap(\.blocks))
                    let pdf = PrintPdf().cookbook(title: title, contents: contents,
                                                  recipes: recipes.map { ($0.name, $0.blocks) }, photos: photos)
                    // A folder of its own, emptied first: one cookbook at a time, named for its list.
                    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("cookbook", isDirectory: true)
                    do {
                        try? FileManager.default.removeItem(at: folder)
                        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                        let file = folder.appendingPathComponent(ShareFile.fileName(title, extension: "pdf"))
                        try pdf.write(to: file, options: .atomic)
                        shareURL = file
                        vm.onDone()
                    } catch {
                        vm.onFailed()
                    }
                }
            }
            .alert(
                Strings.printFailed,
                isPresented: Binding(get: { vm?.uiState.failed ?? false }, set: { if !$0 { vm?.onFailureShown() } })
            ) {}
    }
}

/// Presents the system print dialog on `document` once, from this view (an iPad needs a place
/// to point the popover at), and calls `onDone` when it closes.
private struct PrintSheetAnchor: UIViewRepresentable {
    let document: PrintDocument?
    let onDone: () -> Void

    final class Coordinator {
        var presented: UUID?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        guard let document else {
            context.coordinator.presented = nil
            return
        }
        guard context.coordinator.presented != document.id else { return }
        context.coordinator.presented = document.id
        let onDone = onDone
        DispatchQueue.main.async {
            let controller = UIPrintInteractionController.shared
            let info = UIPrintInfo.printInfo()
            info.outputType = .general
            info.jobName = document.name
            controller.printInfo = info
            controller.printingItem = document.data
            let done: UIPrintInteractionController.CompletionHandler = { _, _, _ in onDone() }
            if UIDevice.current.userInterfaceIdiom == .pad {
                controller.present(from: view.bounds, in: view, animated: true, completionHandler: done)
            } else {
                controller.present(animated: true, completionHandler: done)
            }
        }
    }
}
