import Foundation

/// Every piece of UI copy, in one place, the way Android keeps it in res/values/strings.xml.
/// English only. Android collapses runs of whitespace in unquoted resources, so the double
/// spaces in strings like "‹  Back" never rendered there; they are single spaces here.
/// `RecipeShareText` keeps its own English wording by design (it builds a message body).
enum Strings {
    // Common actions
    static let exit = "✕ Exit"
    static let tryAgain = "Try again"
    static let startCooking = "Start cooking"
    static let cancel = "Cancel"
    static let delete = "Delete"
    static let undo = "Undo"
    static let pause = "Pause"
    static let resume = "Resume"
    static let reset = "Reset"
    static let go = "Go"

    // Recipe screen
    static let shareRecipe = "Share recipe"
    static let moreOptions = "More options"
    static func deleteRecipeTitle(_ name: String) -> String { "Delete \"\(name)\"?" }
    static let deleteRecipeBody = "This removes it from history and any lists it's in. This can't be undone."
    static let labelPrep = "Prep"
    static let labelCook = "Cook"
    static let labelTotal = "Total"
    static let headingIngredients = "Ingredients"
    static let headingInstructions = "Instructions"
    static let headingNotes = "Notes"
    static let notesPlaceholder = "Add a note"
    static let openOriginal = "Open original"
    static func openOriginalHint(_ domain: String) -> String { "Open the original recipe on \(domain)" }

    // Errors
    static let errorNoRecipeFound = "Couldn't find recipe data on this page. Some sites don't tag their recipes in a way this app can read yet."
    static func errorFetchFailed(_ detail: String?) -> String { "Couldn't load that page (\(detail ?? "unknown error"))." }
    static func errorBlocked(_ status: Int) -> String { "The site didn't let the app in (HTTP \(status)). Sites often do this for a moment — try again in a minute." }
    static let errorOffline = "You're offline. The recipe will load when you're back online."
    static let errorSaveFailed = "Couldn't save that recipe."
    static let errorNotSaved = "That recipe is no longer saved."
    static let errorNothingToShow = "There's no recipe to show."
    static let errorInvalidUrl = "That doesn't look like a link."

    static func message(for error: ParseError) -> String {
        switch error {
        case .noRecipeFound: return errorNoRecipeFound
        case .blocked(let status): return errorBlocked(status)
        case .offline: return errorOffline
        case .fetchFailed(let detail, _): return errorFetchFailed(detail)
        case .saveFailed: return errorSaveFailed
        case .notSaved: return errorNotSaved
        case .nothingToShow: return errorNothingToShow
        }
    }

    // Serves / units row
    static let serves = "Serves"
    static let makes = "Makes"
    static let decreaseServings = "Decrease servings"
    static let increaseServings = "Increase servings"
    static let decreaseAmount = "Decrease amount"
    static let increaseAmount = "Increase amount"
    static func originalServings(_ yield: String) -> String { "Original: \(yield)" }
    static func servings(_ n: Int) -> String { n == 1 ? "\(n) serving" : "\(n) servings" }
    static let changeUnits = "Change units"
    static let unitsMenuHeader = "Units · every recipe"

    static func unitLabel(_ system: UnitSystem) -> String {
        switch system {
        case .asWritten: return "As written"
        case .grams: return "Grams"
        case .ounces: return "Ounces"
        case .metric: return "Metric"
        }
    }

    static func unitDescription(_ system: UnitSystem) -> String {
        switch system {
        case .asWritten: return "Exactly the units the recipe uses"
        case .grams: return "Cups and spoons in grams"
        case .ounces: return "Cups and spoons in ounces and pounds"
        case .metric: return "Grams and millilitres"
        }
    }

    static let convertLiquidsTitle = "Also convert liquids"
    static let convertLiquidsDescription = "Milk, water, oil and other pourables"

    // Cook view
    static func cookStepLabel(_ n: Int) -> String { "STEP \(n)" }
    static func cookPosition(_ n: Int, of total: Int) -> String { "Step \(n) of \(total)" }
    static let cookDoneNext = "Done — next step"
    static let cookDoneFinish = "Done — finish"
    static func cookIngredientsCount(_ n: Int) -> String { " · \(n)" }
    /// The same count on its own line, where the " · " separator would dangle.
    static func cookIngredientsCountOwnLine(_ n: Int) -> String { n == 1 ? "1 item" : "\(n) items" }
    static let hideIngredients = "Hide ingredients"
    static let showIngredients = "Show ingredients"
    static func goToStep(_ n: Int) -> String { "Go to step \(n)" }
    static func timerStart(_ label: String) -> String { "⏱ Start \(label) timer" }
    static func timerRunning(_ clock: String) -> String { "⏱ \(clock)" }
    static let timesUp = "Time's up"

    // Home
    static let homeTitle = "Recipe Clipper"
    static let homeSubtitle = "Share a recipe link to this app from your browser, or paste one here."
    static let labelRecipeUrl = "Recipe URL"
    static let sectionContinueCooking = "Continue cooking"
    static let sectionRecentlyViewed = "Recently viewed"
    static let homeEmptyHint = "Recipes you open will show up here."
    static let navHistory = "History"

    // History
    static let historyTitle = "History"
    static let historyEmpty = "Nothing yet. Recipes you open are kept here automatically."
    static func historyNoResults(_ query: String) -> String { "No recipes match \"\(query)\"." }
    static let searchHistory = "Search titles and ingredients"
    static let clearSearch = "Clear search"
    static func deletedOne(_ title: String) -> String { "Deleted \"\(title)\"" }
    static func deletedMany(_ n: Int) -> String { n == 1 ? "\(n) recipe deleted" : "\(n) recipes deleted" }

    /// One snackbar message for the whole pending batch, or nil when nothing is pending.
    static func deletedMessage(_ pending: [String]) -> String? {
        switch pending.count {
        case 0: return nil
        case 1: return deletedOne(pending[0])
        default: return deletedMany(pending.count)
        }
    }

    // Shared
    static let tagSaved = "Saved"

    // Relative times. TimeAgo decides which applies; the words live here.
    static let timeJustNow = "Just now"
    static let timeYesterday = "Yesterday"
    static let timeDateFormat = "MMM d"
    static func timeMinutesAgo(_ n: Int) -> String { "\(n) min ago" }
    static func timeHoursAgo(_ n: Int) -> String { "\(n) h ago" }
    static func timeDaysAgo(_ n: Int) -> String { n == 1 ? "\(n) day ago" : "\(n) days ago" }

    static func elapsed(_ elapsed: Elapsed) -> String {
        switch elapsed {
        case .justNow: return timeJustNow
        case .minutes(let n): return timeMinutesAgo(n)
        case .hours(let n): return timeHoursAgo(n)
        case .yesterday: return timeYesterday
        case .days(let n): return timeDaysAgo(n)
        case .onDate(let millis):
            let formatter = DateFormatter()
            formatter.setLocalizedDateFormatFromTemplate(timeDateFormat)
            return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
        }
    }

    static let darkWhileCookingTitle = "Dark while cooking"
    static let darkWhileCookingDescription = "Keep cook mode on an ink screen in light mode"

    // Settings
    static let navSettings = "Settings"
    static let settingsTitle = "Settings"
    static let settingsSectionUnits = "Units"
    static let settingsSectionOvenTemperature = "Oven temperature"
    static let settingsSectionAppearance = "Appearance"

    static func temperatureLabel(_ unit: TemperatureUnit) -> String {
        switch unit {
        case .asWritten: return "As written"
        case .celsius: return "Celsius (°C)"
        case .fahrenheit: return "Fahrenheit (°F)"
        }
    }

    static func temperatureDescription(_ unit: TemperatureUnit) -> String {
        switch unit {
        case .asWritten: return "Exactly the temperature the recipe uses"
        case .celsius: return "Oven temperatures converted to °C"
        case .fahrenheit: return "Oven temperatures converted to °F"
        }
    }

    // Lists
    static let navLists = "Lists"
    static let listsTitle = "Lists"
    static let newList = "+ New list"
    static let create = "Create"
    static let rename = "Rename"
    static let save = "Save"
    static let listName = "List name"
    static let listEmpty = "Nothing in this list yet."

    /// "Empty" rather than "0 recipes": a state, not a tally.
    static func listCount(_ n: Int) -> String {
        switch n {
        case 0: return "Empty"
        case 1: return "1 recipe"
        default: return "\(n) recipes"
        }
    }

    // Save-to-list sheet
    static let saveToListTitle = "Save to"
    static let saveToList = "Save to a list"
    static let inAList = "Saved to a list"

    // List detail
    static let renameListTitle = "Rename list"
    static func deleteListTitle(_ name: String) -> String { "Delete \"\(name)\"?" }
    /// Deliberately says what does NOT happen: the recipes are not deleted.
    static let deleteListBody = "The list is removed. The recipes in it stay in your history."
    static let deleteList = "Delete list"
}
