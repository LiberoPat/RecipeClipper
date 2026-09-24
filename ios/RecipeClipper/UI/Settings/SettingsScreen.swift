import SwiftUI

/// The app's one settings surface. Exclusive choices are radio rows, independent toggles are
/// switches — never a bare checkmark for either, which is the reason this screen exists.
struct SettingsScreen: View {
    let vm: SettingsViewModel

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(Strings.settingsTitle, style: Typography.headlineSmall)
                    .padding(.bottom, 12)

                SectionHeading(Strings.settingsSectionUnits).padding(.bottom, 4)
                ForEach(UnitSystem.allCases, id: \.self) { option in
                    RadioRow(
                        title: Strings.unitLabel(option),
                        description: Strings.unitDescription(option),
                        selected: option == state.unitSystem
                    ) { vm.onUnitSystemChange(option) }
                }
                if vm.showsConvertLiquids {
                    SwitchRow(
                        title: Strings.convertLiquidsTitle,
                        description: Strings.convertLiquidsDescription,
                        isOn: Binding(get: { vm.uiState.convertLiquids }, set: vm.onConvertLiquidsChange)
                    )
                }

                Divided {
                    SectionHeading(Strings.settingsSectionOvenTemperature).padding(.bottom, 4)
                    ForEach(TemperatureUnit.allCases, id: \.self) { option in
                        RadioRow(
                            title: Strings.temperatureLabel(option),
                            description: Strings.temperatureDescription(option),
                            selected: option == state.temperatureUnit
                        ) { vm.onTemperatureUnitChange(option) }
                    }
                }

                Divided {
                    SectionHeading(Strings.settingsSectionAppearance).padding(.bottom, 4)
                    SwitchRow(
                        title: Strings.darkWhileCookingTitle,
                        description: Strings.darkWhileCookingDescription,
                        isOn: Binding(get: { vm.uiState.darkWhileCooking }, set: vm.onDarkWhileCookingChange)
                    )
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// A section preceded by a hairline with breathing room either side.
private struct Divided<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        Hairline().padding(.vertical, 16)
        content()
    }
}

/// An exclusive-choice row: a radio circle, a title and a one-line description. The whole row
/// is the tap target.
private struct RadioRow: View {
    let title: String
    let description: String
    let selected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 12) {
                RadioGlyph(selected: selected)
                TitleAndDescription(title: title, description: description)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// An independent toggle: a title, a description and a switch.
private struct SwitchRow: View {
    let title: String
    let description: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            TitleAndDescription(title: title, description: description)
        }
        .toggleStyle(StackedWhenLargeToggleStyle())
        .tint(Palette.primary)
        .padding(.vertical, 10)
    }
}

/// The system switch beside its label normally. At the accessibility sizes the label gets the
/// full width and the switch sits under it: beside a 51pt switch the description was wrapping
/// two or three words to a line.
private struct StackedWhenLargeToggleStyle: ToggleStyle {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func makeBody(configuration: Configuration) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                // Hidden from VoiceOver: the switch below still carries it as its label.
                configuration.label.accessibilityHidden(true)
                Toggle(configuration).labelsHidden()
            }
        } else {
            Toggle(configuration)
        }
    }
}

private struct TitleAndDescription: View {
    let title: String
    let description: String
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
            Text(description).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
