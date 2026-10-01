import SwiftUI

/// An item's edit sheet (#194; Android's `EditSheet`): its stock, name, quantity, staple switch
/// and use-by date, and Delete.
struct PantryEditSheet: View {
    let editing: PantryEditing
    let vm: PantryViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeading(Strings.pantryEditTitle)
                // Every state, visibly (#194): the row's menu and swipes are shortcuts. Applied
                // at once, as they are.
                Picker(
                    Strings.pantryEditTitle,
                    selection: Binding(get: { vm.uiState.editing?.stock ?? editing.stock }, set: vm.onEditStock)
                ) {
                    ForEach(PantryStock.allCases, id: \.self) { choice in
                        Text(PantryRow.label(choice)).tag(choice)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .accessibilityIdentifier("pantryEditStock")
                OutlinedField(
                    label: Strings.pantryLabelName,
                    text: Binding(get: { vm.uiState.editing?.name ?? "" }, set: vm.onEditName)
                )
                .accessibilityIdentifier("pantryEditName")
                OutlinedField(
                    label: Strings.pantryLabelQuantity,
                    text: Binding(get: { vm.uiState.editing?.quantity ?? "" }, set: vm.onEditQuantity)
                )
                .accessibilityIdentifier("pantryEditQuantity")
                Toggle(isOn: Binding(get: { vm.uiState.editing?.alwaysHave ?? false }, set: vm.onEditAlwaysHave)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Strings.pantryAlwaysHave).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                        Text(Strings.pantryAlwaysHaveDetail).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                    }
                }
                .tint(Palette.primary)
                .accessibilityIdentifier("pantryEditAlwaysHave")
                expiry
                if let bought = editing.purchasedDay {
                    Text(Strings.pantryBought(PantryDate.short(bought))).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                }
                HStack {
                    Button(Strings.delete, role: .destructive, action: vm.onEditDelete)
                        .buttonStyle(TextActionStyle(color: Palette.accentText))
                        .accessibilityIdentifier("pantryEditDelete")
                    Spacer()
                    Button(Strings.save, action: vm.onEditSave)
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled((vm.uiState.editing?.name ?? "").kIsBlank)
                        .accessibilityIdentifier("pantryEditSave")
                }
                .padding(.top, 8)
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .presentationBackground(Palette.background)
    }

    /// The use-by date: none, or a date picked in a UTC calendar (an epoch day's own).
    @ViewBuilder
    private var expiry: some View {
        let day = vm.uiState.editing?.expiresDay
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Strings.pantryLabelExpiry).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                if day == nil {
                    Text(Strings.pantryNoDate).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                }
            }
            Spacer()
            if let day {
                DatePicker(
                    Strings.pantryLabelExpiry,
                    selection: Binding(get: { PlanDays.utcDate(day) }, set: { vm.onEditExpiry(PantryDate.day($0)) }),
                    displayedComponents: .date
                )
                .labelsHidden()
                .environment(\.timeZone, TimeZone(identifier: "UTC")!)
                Button(Strings.clearDate) { vm.onEditExpiry(nil) }
                    .buttonStyle(TextActionStyle(color: Palette.accentText))
            } else {
                Button(Strings.setDate) { vm.onEditExpiry(vm.uiState.today) }
                    .buttonStyle(TextActionStyle(color: Palette.accentText))
            }
        }
    }
}
