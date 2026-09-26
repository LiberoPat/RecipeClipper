import CoreText
import SwiftUI
import UIKit
import os

/// The share target: iOS's counterpart of Android's ACTION_SEND handling. It runs the import
/// itself, in the extension, and saves into the database the app reads (both live in the App
/// Group container), then shows a small confirmation card. Apple offers share extensions no
/// supported way to open their app, so the app is not opened (issue #19); the recipe is
/// waiting at the top of Home's "Continue cooking".
///
/// The import is the app's own code, compiled into this target too (project.yml): the
/// repository, the source, the parsers and the SQLite layer, not a copy.
final class ShareViewController: UIViewController {

    private let viewModel: ShareImportViewModel
    private var started = false

    override init(nibName: String?, bundle: Bundle?) {
        viewModel = ShareImportViewModel(repository: Self.makeRepository(), connectivity: PathConnectivity())
        super.init(nibName: nibName, bundle: bundle)
    }

    required init?(coder: NSCoder) {
        viewModel = ShareImportViewModel(repository: Self.makeRepository(), connectivity: PathConnectivity())
        super.init(coder: coder)
    }

    /// The same repository the app builds, over the shared database file. Nil if the App
    /// Group container is missing (this build isn't entitled to it) or the file won't open.
    /// No rendered (off-screen WebView) fallback here: it would cost the extension memory it
    /// doesn't have, so a page that needs it fails in the card and works from the app.
    private static func makeRepository() -> RecipeRepository? {
        guard let path = AppDatabase.sharedPath() else {
            shareLog.error("App Group container unavailable; can't save")
            return nil
        }
        do {
            let clock = SystemClock()
            // The limit (#107) the app mirrors into the App Group suite: this process never
            // sees the flags or StoreKit.
            let library = DefaultsLibraryLimit(defaults: UserDefaults(suiteName: AppGroup.identifier) ?? .standard)
            return DefaultRecipeRepository(
                db: try AppDatabase(path: path), source: BlogRecipeSource(), clock: clock, library: library
            )
        } catch {
            shareLog.error("couldn't open the shared database: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        MemoryFootprint.log("launched")
        ShareFonts.registerFromContainingApp()
        view.backgroundColor = .clear

        let host = UIHostingController(rootView: ShareImportView(
            vm: viewModel,
            onDone: { [weak self] in self?.finish() },
            onCancel: { [weak self] in self?.cancel() }
        ))
        host.view.backgroundColor = .clear
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        host.didMove(toParent: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        Task { @MainActor [viewModel] in
            let input = await SharedItems.read(from: providers)
            MemoryFootprint.log("read the shared items")
            viewModel.start(with: input)
            await viewModel.currentLoad?.value
            MemoryFootprint.log("import finished")
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        // However the card went away, an import still running stops and writes nothing.
        viewModel.onCancel()
    }

    private var completed = false

    private func finish() {
        guard !completed else { return }
        completed = true
        viewModel.onCancel()
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }

    private func cancel() {
        guard !completed else { return }
        completed = true
        viewModel.onCancel()
        extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }
}

let shareLog = Logger(subsystem: "com.liberopat.recipeclipper", category: "share")

/// The fonts are bundled once, in the app. The extension sits inside the app's bundle
/// (`RecipeClipper.app/PlugIns/RecipeClipperShare.appex`), so it registers them from there
/// for its own process. If that ever fails, text falls back to the system font.
enum ShareFonts {
    static func registerFromContainingApp() {
        let appBundle = Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent()
        for name in ["fraunces.ttf", "karla.ttf"] {
            let url = appBundle.appendingPathComponent(name)
            var error: Unmanaged<CFError>?
            if !CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error) {
                shareLog.error("font \(name, privacy: .public) not registered: \(String(describing: error?.takeRetainedValue()), privacy: .public)")
            }
        }
    }
}

/// Extensions get far less memory than apps (about 120 MB for a share extension on current
/// devices; Apple doesn't document the figure), so debug builds log the process's footprint and
/// its peak at each step. Read them in Console.app on a device (subsystem
/// com.liberopat.recipeclipper, category share); see docs/testing.md.
enum MemoryFootprint {
    static func log(_ step: StaticString) {
        #if DEBUG
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return }
        let mb = { (bytes: UInt64) in String(format: "%.1f", Double(bytes) / 1_048_576) }
        shareLog.notice("memory \(step, privacy: .public): footprint \(mb(info.phys_footprint), privacy: .public) MB, peak \(mb(UInt64(max(0, info.ledger_phys_footprint_peak))), privacy: .public) MB")
        #endif
    }
}
