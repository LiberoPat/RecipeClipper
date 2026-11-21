import Foundation

/// Settings' sections (#286; Android's `SettingsSection`), in the index's order: each is a row
/// there, with its current value, and a page of its own (`Route.settingsSection`). `unlimited`
/// shows only with the `freeTier` flag and `proUnlock` off, `pro` (#413, the Pro screen itself) only
/// with `proUnlock`, `week` (the evening and Tonight reminders, #397) only with `eveningReminders`.
enum SettingsSection: String, CaseIterable, Hashable {
    case units
    case ovenTemperature = "oven-temperature"
    case appearance
    case steps
    case week
    case pantry
    case yourRecipes = "your-recipes"
    case help
    case unlimited
    case pro

    var title: String {
        switch self {
        case .units: Strings.settingsSectionUnits
        case .ovenTemperature: Strings.settingsSectionOvenTemperature
        case .appearance: Strings.settingsSectionAppearance
        case .steps: Strings.settingsSectionSteps
        case .week: Strings.tabWeek
        case .pantry: Strings.settingsSectionPantry
        case .yourRecipes: Strings.settingsSectionYourRecipes
        case .help: Strings.settingsSectionHelp
        case .unlimited: Strings.unlimitedTitle
        case .pro: Strings.proTitle
        }
    }

    /// The SF Symbol beside its name on the index.
    var symbol: String {
        switch self {
        case .units: "scalemass"
        case .ovenTemperature: "thermometer.medium"
        case .appearance: "moon"
        case .steps: "checklist"
        case .week: "calendar"
        case .pantry: "bell"
        case .yourRecipes: "icloud"
        case .help: "info.circle"
        case .unlimited: "infinity"
        case .pro: "star"
        }
    }

    /// The row's and the page's accessibility identifier: `settings.units`, `settings.help`…
    var identifier: String { "settings.\(rawValue)" }
}
