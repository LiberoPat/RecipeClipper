import Foundation

/// Settings' sections (#286; Android's `SettingsSection`), in the index's order: each is a row
/// there, with its current value, and a page of its own (`Route.settingsSection`). `unlimited`
/// shows only with the `freeTier` flag.
enum SettingsSection: String, CaseIterable, Hashable {
    case units
    case ovenTemperature = "oven-temperature"
    case appearance
    case steps
    case pantry
    case yourRecipes = "your-recipes"
    case help
    case unlimited

    var title: String {
        switch self {
        case .units: Strings.settingsSectionUnits
        case .ovenTemperature: Strings.settingsSectionOvenTemperature
        case .appearance: Strings.settingsSectionAppearance
        case .steps: Strings.settingsSectionSteps
        case .pantry: Strings.settingsSectionPantry
        case .yourRecipes: Strings.settingsSectionYourRecipes
        case .help: Strings.settingsSectionHelp
        case .unlimited: Strings.unlimitedTitle
        }
    }

    /// The SF Symbol beside its name on the index.
    var symbol: String {
        switch self {
        case .units: "scalemass"
        case .ovenTemperature: "thermometer.medium"
        case .appearance: "moon"
        case .steps: "checklist"
        case .pantry: "bell"
        case .yourRecipes: "icloud"
        case .help: "info.circle"
        case .unlimited: "infinity"
        }
    }

    /// The row's and the page's accessibility identifier: `settings.units`, `settings.help`…
    var identifier: String { "settings.\(rawValue)" }
}
