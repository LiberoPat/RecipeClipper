import SwiftUI

/// "Add from this file" (#149, phase 2; Android's ReceiveFileSheet): the recipes, grocery items
/// (each with its recipe) and pantry items a shared file holds, each ticked to start, the pantry
/// items' destination, and one Add.
struct ReceiveFileView: View {
    let vm: ReceiveFileViewModel
    let onDone: () -> Void

    var body: some View {
        let state = vm.uiState
        VStack(alignment: .leading, spacing: 0) {
            SectionHeading(Strings.receiveFileTitle).padding(.bottom, 8)
            if let skipped = state.skippedFree {
                quiet(Strings.receiveFileSkippedFree(skipped.skipped, limit: skipped.limit))
                Button(Strings.done, action: onDone)
                    .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                    .padding(.top, 16)
                    .accessibilityIdentifier("receiveFileDone")
            } else {
                if let error = state.error {
                    Text(Strings.message(for: error))
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.error)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, 8)
                }
                if state.isEmpty {
                    if state.error == nil { quiet(Strings.receiveFileEmpty) }
                } else {
                    section(Strings.tabRecipes, state.recipes, state)
                    section(Strings.tabGroceries, state.groceries, state)
                    section(Strings.tabPantry, state.pantry, state)
                    if !state.pantry.isEmpty {
                        destination(Strings.receiveFileToPantry, .pantry, state).padding(.top, 4)
                        destination(Strings.receiveFileToGroceries, .groceries, state)
                    }
                    Button(Strings.add, action: vm.onAdd)
                        .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                        .disabled(state.tickedCount == 0 || state.adding || state.added != nil)
                        .padding(.top, 16)
                        .accessibilityIdentifier("receiveFileAdd")
                }
            }
        }
    }

    @ViewBuilder
    private func section(_ heading: String, _ rows: [ReceivedRow], _ state: ReceiveFileUiState) -> some View {
        if !rows.isEmpty {
            Text(heading)
                .textStyle(Typography.titleMedium)
                .foregroundStyle(Palette.onBackground)
                .padding(.top, 12)
                .padding(.bottom, 4)
                .accessibilityAddTraits(.isHeader)
            ForEach(rows) { row in
                let ticked = !state.unticked.contains(row.key)
                Button { vm.onToggle(row.key) } label: {
                    HStack(spacing: 12) {
                        CheckboxGlyph(checked: ticked)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.text).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                            if let detail = row.detail { quiet(detail) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(ticked ? [.isSelected] : [])
                .accessibilityIdentifier("receiveRow-\(row.key)")
            }
        }
    }

    private func destination(_ label: String, _ value: PantryDestination, _ state: ReceiveFileUiState) -> some View {
        let selected = state.pantryTo == value
        return Button { vm.onPantryTo(value) } label: {
            HStack(spacing: 12) {
                RadioGlyph(selected: selected)
                Text(label).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityIdentifier("receivePantryTo-\(value.rawValue)")
    }

    private func quiet(_ text: String) -> some View {
        Text(text)
            .textStyle(Typography.bodyMedium)
            .foregroundStyle(Palette.muted)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The sheet a shared file opens, over whatever is on screen. Once the things are added it
/// closes and `onAdded` says where they went, so the app can show them.
struct ReceiveFileSheet: ViewModifier {
    /// Nil (a container with no share file repository) never shows it.
    let vm: ReceiveFileViewModel?
    let onAdded: (ReceivedWhere) -> Void

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: Binding(get: { vm?.uiState.open ?? false }, set: { if !$0 { vm?.onDismiss() } })) {
                if let vm {
                    ScrollView {
                        ReceiveFileView(vm: vm, onDone: done)
                            .padding(.horizontal, 20)
                            .readableColumn()
                            .padding(.vertical, 24)
                    }
                    .presentationBackground(Palette.background)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
                }
            }
            .onChange(of: vm?.uiState.added) { _, added in
                guard added != nil, vm?.uiState.skippedFree == nil else { return }
                done()
            }
    }

    private func done() {
        let added = vm?.uiState.added
        vm?.onDismiss()
        if let added { onAdded(added) }
    }
}

/// The platform half of "Send as file" (#149): writes the file where the share sheet can read
/// it, opens the share sheet on it, and says so when it couldn't be made.
struct SendFileEffect: ViewModifier {
    let vm: SendFileViewModel?
    @State private var url: URL?

    // The same modifiers whether or not there is a ViewModel yet (a screen may make it on first
    // use), so the content keeps its identity.
    func body(content: Content) -> some View {
        content
            .background(ShareSheetAnchor(item: url) {
                url = nil
                vm?.onSent()
            })
            .onChange(of: vm?.uiState.file) { _, file in
                guard let vm, let file else { return }
                // A folder of its own, emptied first: one sent file at a time, named for its reader.
                let folder = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
                do {
                    try? FileManager.default.removeItem(at: folder)
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    let written = folder.appendingPathComponent(file.name)
                    try Data(file.text.utf8).write(to: written, options: .atomic)
                    url = written
                } catch {
                    vm.onWriteFailed()
                }
            }
            .alert(
                Strings.sendFileFailed,
                isPresented: Binding(get: { vm?.uiState.failed ?? false }, set: { if !$0 { vm?.onFailureShown() } })
            ) {}
    }
}
