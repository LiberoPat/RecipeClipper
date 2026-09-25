import SwiftUI

/// "Add a menu to this week" (#52): every saved menu with its meal count. Tapping one adds its
/// meals to the week shown, after what is planned there; each row's menu renames or deletes it.
struct MenusSheet: View {
    let vm: WeekViewModel
    /// The name being typed in the Rename prompt.
    @State private var renameText = ""

    var body: some View {
        let menus = vm.uiState.menus.menus
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeading(Strings.applyMenuTitle).padding(.bottom, 12)
                if menus.isEmpty {
                    Text(Strings.menusEmpty)
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.muted)
                }
                ForEach(menus) { menu in
                    HStack(spacing: 8) {
                        Button { vm.onApplyMenu(menu) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(menu.name)
                                    .textStyle(Typography.bodyLarge)
                                    .foregroundStyle(Palette.onBackground)
                                Text(Strings.menuMealCount(menu.mealCount))
                                    .textStyle(Typography.bodyMedium)
                                    .foregroundStyle(Palette.muted)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 8)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("menu-\(menu.id)")
                        Menu {
                            Button(Strings.rename) { vm.onRenameMenuStart(menu) }
                            Button(Strings.delete, role: .destructive) { vm.onDeleteMenuStart(menu) }
                        } label: {
                            Image(systemName: "ellipsis.circle").frame(minWidth: 44, minHeight: 44)
                        }
                        .accessibilityLabel(Strings.moreOptions)
                        .accessibilityIdentifier("menuOptions-\(menu.id)")
                    }
                    Hairline()
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
            .readableColumn()
        }
        .presentationBackground(Palette.background)
        .accessibilityIdentifier("menusSheet")
        // Rename and Delete start here, so their prompts show over the sheet.
        .alert(Strings.renameMenuTitle, isPresented: Binding(
            get: { vm.uiState.menus.renaming != nil }, set: { if !$0 { vm.onRenameMenuDismissed() } }
        )) {
            TextField(Strings.menuNameHint, text: $renameText)
                .accessibilityIdentifier("menuName")
            Button(Strings.rename) { vm.onRenameMenu(renameText) }
            Button(Strings.cancel, role: .cancel, action: vm.onRenameMenuDismissed)
        }
        .alert(Strings.deleteMenuTitle(vm.uiState.menus.deleting?.name ?? ""), isPresented: Binding(
            get: { vm.uiState.menus.deleting != nil }, set: { if !$0 { vm.onDeleteMenuDismissed() } }
        )) {
            Button(Strings.delete, role: .destructive, action: vm.onDeleteMenuConfirm)
            Button(Strings.cancel, role: .cancel, action: vm.onDeleteMenuDismissed)
        } message: {
            Text(Strings.deleteMenuBody)
        }
        .onChange(of: vm.uiState.menus.renaming) { _, menu in if let menu { renameText = menu.name } }
    }
}
