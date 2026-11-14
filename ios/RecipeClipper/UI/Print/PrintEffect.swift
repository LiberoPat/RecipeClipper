import SwiftUI
import UIKit

/// A drawn recipe for the print dialog: its PDF and the job's name.
struct PrintDocument: Equatable {
    let id = UUID()
    let data: Data
    let name: String
}

/// The platform half of printing (#318): draws `PrintViewModel`'s job into a PDF off the main
/// actor (#331), then opens the system print dialog on a recipe (whose share button also saves
/// the PDF) or the share sheet on a cookbook, as Send as file does; an alert when it couldn't be
/// made, or the dialog couldn't open. Leaving the screen drops the job: nothing pops up on return.
/// Android's `PrintEffect`.
struct PrintEffect: ViewModifier {
    let vm: PrintViewModel?
    @State private var shareURL: URL?
    @State private var document: PrintDocument?
    @State private var drawing: Task<Void, Never>?

    // The same modifiers whether or not there is a ViewModel yet, so the content keeps its identity.
    func body(content: Content) -> some View {
        content
            .background(ShareSheetAnchor(item: shareURL) { shareURL = nil })
            .background(PrintSheetAnchor(document: document, onDone: { document = nil }, onFailed: {
                document = nil
                vm?.onFailed()
            }))
            .onChange(of: vm?.uiState.job) { _, job in
                drawing?.cancel()
                guard let vm, let job else { return }
                drawing = Task { await draw(job, for: vm) }
            }
            .onDisappear {
                drawing?.cancel()
                vm?.onLeave()
            }
            .alert(
                Strings.printFailed,
                isPresented: Binding(get: { vm?.uiState.failed ?? false }, set: { if !$0 { vm?.onFailureShown() } })
            ) {}
    }

    @MainActor
    private func draw(_ job: PrintJob, for vm: PrintViewModel) async {
        switch job {
        case .recipe(let title, let blocks):
            let pdf = await Task.detached(priority: .userInitiated) {
                PrintPdf().recipe(blocks, photos: PrintPdf.cachedPhotos(blocks))
            }.value
            guard !Task.isCancelled else { return }
            document = PrintDocument(data: pdf, name: title)
            vm.onDone()
        case .cookbook(let title, let contents, let recipes):
            let file = await Task.detached(priority: .userInitiated) { () -> URL? in
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
                    return file
                } catch {
                    return nil
                }
            }.value
            guard !Task.isCancelled else { return }
            if let file {
                shareURL = file
                vm.onDone()
            } else {
                vm.onFailed()
            }
        }
    }
}

/// Presents the system print dialog on `document` once, from this view (an iPad needs a place
/// to point the popover at), and calls `onDone` when it closes, or `onFailed` if it couldn't open.
private struct PrintSheetAnchor: UIViewRepresentable {
    let document: PrintDocument?
    let onDone: () -> Void
    let onFailed: () -> Void

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
        let onFailed = onFailed
        DispatchQueue.main.async {
            let controller = UIPrintInteractionController.shared
            let info = UIPrintInfo.printInfo()
            info.outputType = .general
            info.jobName = document.name
            controller.printInfo = info
            controller.printingItem = document.data
            let done: UIPrintInteractionController.CompletionHandler = { _, _, _ in onDone() }
            let opened = UIDevice.current.userInterfaceIdiom == .pad
                ? controller.present(from: view.bounds, in: view, animated: true, completionHandler: done)
                : controller.present(animated: true, completionHandler: done)
            // False means the dialog never opened (another is up, or printing is unavailable): say so (#331).
            if !opened { onFailed() }
        }
    }
}
