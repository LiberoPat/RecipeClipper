import SwiftUI

/// The Save prompt, menus sheet and snackbar of the Week tab (#52; Android's WeekMenus), each
/// shown while `MenusUiState` says so. Rename and Delete live on the sheet, which they start from.
struct WeekMenusModifier: ViewModifier {
    let vm: WeekViewModel
    /// The name being typed in the Save prompt; view-local, like Android's
    /// rememberSaveable text.
    @State private var name = ""

    func body(content: Content) -> some View {
        let menus = vm.uiState.menus
        content
            .alert(Strings.saveMenuTitle, isPresented: Binding(
                get: { menus.saving }, set: { if !$0 { vm.onSaveMenuDismissed() } }
            )) {
                TextField(Strings.menuNameHint, text: $name)
                    .accessibilityIdentifier("menuName")
                Button(Strings.save) { vm.onSaveMenu(name) }
                Button(Strings.cancel, role: .cancel, action: vm.onSaveMenuDismissed)
            }
            .onChange(of: menus.saving) { _, saving in if saving { name = "" } }
            .sheet(isPresented: Binding(get: { menus.picking }, set: { if !$0 { vm.onPickMenuDismissed() } })) {
                MenusSheet(vm: vm)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            .overlay(alignment: .bottom) {
                if let message = menus.message {
                    Snackbar(message: Self.text(message), actionLabel: nil)
                        .frame(maxWidth: ReadableWidth.column)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 12)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.easeOut(duration: 0.2), value: menus.message == nil)
            .task(id: menus.message) {
                guard let message = menus.message else { return }
                await SnackbarTimeout.run(pending: [Self.text(message)], onTimeout: vm.onMenuMessageShown)
            }
    }

    /// The snackbar text for a menu action.
    static func text(_ message: MenuMessage) -> String {
        switch message {
        case .saved(let name): Strings.menuSaved(name)
        case .saveFailed: Strings.menuSaveFailed
        case .applied(let name, let count): Strings.menuApplied(count, name)
        }
    }
}
