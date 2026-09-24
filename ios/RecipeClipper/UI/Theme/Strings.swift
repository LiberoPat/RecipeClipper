import Foundation

/// Every piece of UI copy, in one place, the way Android keeps it in res/values/strings.xml.
/// The words live in the String Catalog (`Resources/Localizable.xcstrings`), in English,
/// Spanish, French, German, Italian and Brazilian Portuguese; these are thin accessors so
/// call sites read `Strings.tryAgain` whatever the language. Catalog keys are the Android
/// resource names (plus the specifiers Swift interpolation adds, e.g. `servings %lld`), so a
/// string can be found on both platforms by one name.
///
/// Android collapses runs of whitespace in unquoted resources, so the double spaces in
/// strings like "‹  Back" never rendered there; they are single spaces in the catalog.
/// `SiteReportLink` keeps its English wording by design: it builds a report the maintainer
/// reads, not UI.
enum Strings {
    // Common actions
    static var exit: String { String(localized: "action_exit") }
    static var tryAgain: String { String(localized: "action_try_again") }
    static var reportSite: String { String(localized: "action_report_site") }
    static var startCooking: String { String(localized: "action_start_cooking") }
    static var cancel: String { String(localized: "action_cancel") }
    static var delete: String { String(localized: "action_delete") }
    static var undo: String { String(localized: "action_undo") }
    static var pause: String { String(localized: "action_pause") }
    static var resume: String { String(localized: "action_resume") }
    static var reset: String { String(localized: "action_reset") }
    static var go: String { String(localized: "action_go") }

    // Recipe screen
    static var shareRecipe: String { String(localized: "cd_share_recipe") }
    static var moreOptions: String { String(localized: "cd_more_options") }
    static func deleteRecipeTitle(_ name: String) -> String { String(localized: "delete_recipe_title \(name)") }
    static var deleteRecipeBody: String { String(localized: "delete_recipe_body") }
    static var labelPrep: String { String(localized: "label_prep") }
    static var labelCook: String { String(localized: "label_cook") }
    static var labelTotal: String { String(localized: "label_total") }
    static var headingIngredients: String { String(localized: "heading_ingredients") }
    static var headingInstructions: String { String(localized: "heading_instructions") }
    static var headingNotes: String { String(localized: "heading_notes") }
    static var notesPlaceholder: String { String(localized: "notes_placeholder") }
    static var openOriginal: String { String(localized: "action_open_original") }

    // Editing a recipe, or typing one in (#29)
    static var edit: String { String(localized: "action_edit") }
    static var updateFromSource: String { String(localized: "action_update_from_source") }
    static var update: String { String(localized: "action_update") }
    static var newRecipe: String { String(localized: "action_new_recipe") }
    static var updateFromSourceTitle: String { String(localized: "update_from_source_title") }
    static var updateFromSourceBody: String { String(localized: "update_from_source_body") }
    static func updateFromSourceFailed(_ reason: String) -> String {
        String(localized: "update_from_source_failed \(reason)")
    }
    static var editTitleNew: String { String(localized: "edit_title_new") }
    static var editTitleEdit: String { String(localized: "edit_title_edit") }
    static var editLabelName: String { String(localized: "edit_label_name") }
    static var editLabelYield: String { String(localized: "edit_label_yield") }
    static var editLabelPrep: String { String(localized: "edit_label_prep") }
    static var editLabelCook: String { String(localized: "edit_label_cook") }
    static var editLabelTotal: String { String(localized: "edit_label_total") }
    static var editLabelIngredients: String { String(localized: "edit_label_ingredients") }
    static var editLabelSteps: String { String(localized: "edit_label_steps") }
    static var editLabelPhoto: String { String(localized: "edit_label_photo") }
    static var editErrorInvalid: String { String(localized: "edit_error_invalid") }
    static var editErrorSaveFailed: String { String(localized: "edit_error_save_failed") }
    static func openOriginalHint(_ domain: String) -> String { String(localized: "cd_open_original \(domain)") }

    // Clip it yourself (#37)
    static let clipOffer = "Open the page, select the name, ingredients and steps, and tap where each one goes."
    static let clipItYourself = "Clip it yourself"
    static let done = "Done"
    static let discard = "Discard"
    static let remove = "Remove"
    static func clipField(_ field: ClipField) -> String {
        switch field {
        case .name: return "Name"
        case .ingredients: return "Ingredients"
        case .steps: return "Steps"
        case .photo: return "Photo"
        }
    }
    static func clipTagCount(_ label: String, _ n: Int) -> String { "\(label) · \(n)" }
    static let clipHint = "Select text on the page, then tap where it goes. For the photo, tap Photo, then the picture."
    static let clipPickingPhoto = "Tap the picture to use as the photo."
    static func clipLinesSelected(_ n: Int) -> String {
        (n == 1 ? "1 line selected" : "\(n) lines selected") + " · each line becomes one item"
    }
    static func clipSummary(_ draft: ClipDraft) -> String {
        let ingredients = draft.count(.ingredients)
        let steps = draft.count(.steps)
        return [
            draft.count(.name) > 0 ? "Name ✓" : "No name",
            ingredients == 1 ? "1 ingredient" : "\(ingredients) ingredients",
            steps == 1 ? "1 step" : "\(steps) steps",
            draft.photo != nil ? "photo" : "no photo",
        ].joined(separator: " · ")
    }
    static let clipReview = "Review"
    static func clipMessage(_ message: ClipMessage) -> String {
        switch message {
        case .assigned(.name, _): return "Name added"
        case .assigned(.ingredients, let n): return n == 1 ? "1 ingredient added" : "\(n) ingredients added"
        case .assigned(.steps, let n): return n == 1 ? "1 step added" : "\(n) steps added"
        case .assigned(.photo, _): return "Photo added"
        case .cleared(let field): return "\(clipField(field)) cleared"
        case .draftRestored: return "Draft restored"
        case .saveFailed: return "Couldn't save the clip. Try again."
        }
    }
    static let clipBackToPage = "‹ Back to page"
    static let clipPhotoFromPage = "Photo from the page"
    static let clipNoPhoto = "No photo. Tap Photo on the page, then the picture."
    static let clipServes = "Serves"
    static let clipTotalTime = "Total time"
    static let clipOptional = "optional"
    static func clipIngredientsHeading(_ n: Int) -> String { "Ingredients · \(n)" }
    static func clipStepsHeading(_ n: Int) -> String { "Steps · \(n)" }
    static let clipAddLine = "+ Add a line"
    static let clipAddStep = "+ Add a step"
    static func clipRemoveLine(_ n: Int) -> String { "Remove line \(n)" }
    static let clipSave = "Save recipe"

    // Errors
    static var errorNoRecipeFound: String { String(localized: "error_no_recipe_found") }
    static func errorFetchFailed(_ detail: String?) -> String {
        let detail = detail ?? String(localized: "error_fetch_failed_unknown_detail")
        return String(localized: "error_fetch_failed \(detail)")
    }
    static func errorBlocked(_ status: Int) -> String { String(localized: "error_blocked \(status)") }
    static var errorOffline: String { String(localized: "error_offline") }
    static var errorSaveFailed: String { String(localized: "error_save_failed") }
    static var errorNotSaved: String { String(localized: "error_not_saved") }
    static var errorNothingToShow: String { String(localized: "error_nothing_to_show") }
    static var errorInvalidUrl: String { String(localized: "error_invalid_url") }

    // Settings → Your recipes: export and import (#26)
    static var settingsSectionYourRecipes: String { String(localized: "settings_section_your_recipes") }
    static var backupExportTitle: String { String(localized: "backup_export_title") }
    static var backupExportDescription: String { String(localized: "backup_export_description") }
    static var backupImportTitle: String { String(localized: "backup_import_title") }
    static var backupImportDescription: String { String(localized: "backup_import_description") }
    static var backupExporting: String { String(localized: "backup_exporting") }
    static var backupImporting: String { String(localized: "backup_importing") }

    static func backupRecipes(_ n: Int) -> String { String(localized: "backup_recipes \(n)") }
    static func backupLists(_ n: Int) -> String { String(localized: "backup_lists \(n)") }

    /// "Imported 12 recipes and 3 lists." plus what was already here and what didn't fit.
    static func importSummary(_ s: ImportSummary) -> String {
        var parts: [String] = []
        if s.recipesAdded == 0 && s.listsAdded == 0 {
            parts.append(String(localized: "backup_imported_nothing"))
        } else if s.listsAdded == 0 {
            parts.append(String(localized: "backup_imported_recipes \(backupRecipes(s.recipesAdded))"))
        } else {
            parts.append(String(
                localized: "backup_imported \(backupRecipes(s.recipesAdded)) \(backupLists(s.listsAdded))"))
        }
        if s.recipesAlreadyHere > 0 && (s.recipesAdded > 0 || s.listsAdded > 0) {
            parts.append(String(localized: "backup_already_here \(s.recipesAlreadyHere)"))
        }
        if s.recipesSkipped > 0 {
            parts.append(s.recipesSkipped == 1
                ? String(localized: "backup_skipped_one \(historyLimit)")
                : String(localized: "backup_skipped_other \(s.recipesSkipped) \(historyLimit)"))
        }
        return parts.joined(separator: " ")
    }

    static func message(for error: BackupError) -> String {
        switch error {
        case .notABackup: return String(localized: "backup_error_not_a_backup")
        case .newerVersion(let found):
            return String(localized: "backup_error_newer_version \(found)")
        case .malformed(let detail): return String(localized: "backup_error_malformed \(detail)")
        case .readFailed: return String(localized: "backup_error_read_failed")
        case .saveFailed: return String(localized: "backup_error_save_failed")
        case .exportFailed: return String(localized: "backup_error_export_failed")
        }
    }

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
    static var serves: String { String(localized: "label_serves") }
    static var makes: String { String(localized: "label_makes") }
    static var decreaseServings: String { String(localized: "cd_decrease_servings") }
    static var increaseServings: String { String(localized: "cd_increase_servings") }
    static var decreaseAmount: String { String(localized: "cd_decrease_amount") }
    static var increaseAmount: String { String(localized: "cd_increase_amount") }
    static func originalServings(_ yield: String) -> String { String(localized: "original_servings \(yield)") }
    static func servings(_ n: Int) -> String { String(localized: "servings \(n)") }
    static var changeUnits: String { String(localized: "cd_change_units") }
    static var unitsMenuHeader: String { String(localized: "units_menu_header") }

    static func unitLabel(_ system: UnitSystem) -> String {
        switch system {
        case .asWritten: return String(localized: "unit_as_written")
        case .ounces: return String(localized: "unit_ounces")
        case .metric: return String(localized: "unit_metric")
        }
    }

    static func unitDescription(_ system: UnitSystem) -> String {
        switch system {
        case .asWritten: return String(localized: "unit_as_written_description")
        case .ounces: return String(localized: "unit_ounces_description")
        case .metric: return String(localized: "unit_metric_description")
        }
    }

    static var convertLiquidsTitle: String { String(localized: "convert_liquids_title") }
    static var convertLiquidsDescription: String { String(localized: "convert_liquids_description") }

    // Cook view
    static func cookStepLabel(_ n: Int) -> String { String(localized: "cook_step_label \(n)") }
    static func cookPosition(_ n: Int, of total: Int) -> String { String(localized: "cook_position \(n) \(total)") }
    static var cookDoneNext: String { String(localized: "cook_done_next") }
    static var cookDoneFinish: String { String(localized: "cook_done_finish") }
    /// Not words, so not in the catalog.
    static func cookIngredientsCount(_ n: Int) -> String { " · \(n)" }
    /// The same count on its own line, where the " · " separator would dangle.
    static func cookIngredientsCountOwnLine(_ n: Int) -> String { String(localized: "cook_items \(n)") }
    static var hideIngredients: String { String(localized: "cd_hide_ingredients") }
    static var showIngredients: String { String(localized: "cd_show_ingredients") }
    static func goToStep(_ n: Int) -> String { String(localized: "cd_go_to_step \(n)") }
    static func timerStart(_ label: String) -> String { String(localized: "timer_start \(label)") }
    /// Not words, so not in the catalog.
    static func timerRunning(_ clock: String) -> String { "⏱ \(clock)" }
    static var timesUp: String { String(localized: "timers_up") }

    // Home
    /// The app's name, not translated.
    static let homeTitle = "Recipe Clipper"
    static var homeSubtitle: String { String(localized: "home_subtitle") }
    static var labelRecipeUrl: String { String(localized: "label_recipe_url") }
    static var sectionContinueCooking: String { String(localized: "section_continue_cooking") }
    static var sectionRecentlyViewed: String { String(localized: "section_recently_viewed") }
    static var homeEmptyHint: String { String(localized: "home_empty_hint") }
    static var navHistory: String { String(localized: "nav_history") }

    // History
    static var historyTitle: String { String(localized: "history_title") }
    static var historyEmpty: String { String(localized: "history_empty") }
    static func historyNoResults(_ query: String) -> String { String(localized: "history_no_results \(query)") }
    static var searchHistory: String { String(localized: "label_search_history") }
    static var clearSearch: String { String(localized: "cd_clear_search") }
    static func deletedOne(_ title: String) -> String { String(localized: "snackbar_deleted_one \(title)") }
    static func deletedMany(_ n: Int) -> String { String(localized: "snackbar_deleted_many \(n)") }

    /// One snackbar message for the whole pending batch, or nil when nothing is pending.
    static func deletedMessage(_ pending: [String]) -> String? {
        switch pending.count {
        case 0: return nil
        case 1: return deletedOne(pending[0])
        default: return deletedMany(pending.count)
        }
    }

    // Shared
    static var tagSaved: String { String(localized: "tag_saved") }

    // Relative times. TimeAgo decides which applies; the words live here.
    static var timeJustNow: String { String(localized: "time_just_now") }
    static var timeYesterday: String { String(localized: "time_yesterday") }
    /// A template, not a pattern: `setLocalizedDateFormatFromTemplate` orders it per locale
    /// ("Sep 3", "3 sept.", "3. Sept."), so it needs no translation.
    static let timeDateFormat = "MMM d"
    static func timeMinutesAgo(_ n: Int) -> String { String(localized: "time_minutes_ago \(n)") }
    static func timeHoursAgo(_ n: Int) -> String { String(localized: "time_hours_ago \(n)") }
    static func timeDaysAgo(_ n: Int) -> String { String(localized: "time_days_ago \(n)") }

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

    static var darkWhileCookingTitle: String { String(localized: "dark_while_cooking_title") }
    static var darkWhileCookingDescription: String { String(localized: "dark_while_cooking_description") }

    // Settings
    static var navSettings: String { String(localized: "nav_settings") }
    static var settingsTitle: String { String(localized: "settings_title") }
    static var settingsSectionUnits: String { String(localized: "settings_section_units") }
    static var settingsSectionOvenTemperature: String { String(localized: "settings_section_oven_temperature") }
    static var settingsSectionAppearance: String { String(localized: "settings_section_appearance") }

    static func temperatureLabel(_ unit: TemperatureUnit) -> String {
        switch unit {
        case .asWritten: return String(localized: "temperature_as_written")
        case .celsius: return String(localized: "temperature_celsius")
        case .fahrenheit: return String(localized: "temperature_fahrenheit")
        }
    }

    static func temperatureDescription(_ unit: TemperatureUnit) -> String {
        switch unit {
        case .asWritten: return String(localized: "temperature_as_written_description")
        case .celsius: return String(localized: "temperature_celsius_description")
        case .fahrenheit: return String(localized: "temperature_fahrenheit_description")
        }
    }

    // Lists
    static var navLists: String { String(localized: "nav_lists") }
    static var listsTitle: String { String(localized: "lists_title") }
    static var newList: String { String(localized: "action_new_list") }
    static var create: String { String(localized: "action_create") }
    static var rename: String { String(localized: "action_rename") }
    static var save: String { String(localized: "action_save") }
    static var listName: String { String(localized: "label_list_name") }
    static var listEmpty: String { String(localized: "list_empty") }

    /// "Empty" rather than "0 recipes": a state, not a tally.
    static func listCount(_ n: Int) -> String {
        n == 0 ? String(localized: "list_count_empty") : String(localized: "list_count \(n)")
    }

    // Save-to-list sheet
    static var saveToListTitle: String { String(localized: "save_to_list_title") }
    static var saveToList: String { String(localized: "cd_save_to_list") }
    static var inAList: String { String(localized: "cd_in_a_list") }

    // List detail
    static var renameListTitle: String { String(localized: "rename_list_title") }
    static func deleteListTitle(_ name: String) -> String { String(localized: "delete_list_title \(name)") }
    /// Deliberately says what does NOT happen: the recipes are not deleted.
    static var deleteListBody: String { String(localized: "delete_list_body") }
    static var deleteList: String { String(localized: "action_delete_list") }

    // Sharing a recipe out: the labels RecipeShareText writes into the message body.
    static var shareTextLabels: RecipeShareText.Labels {
        RecipeShareText.Labels(
            serves: { String(localized: "share_serves \($0)") },
            makes: { String(localized: "share_makes \($0)") },
            scaled: { line, original in String(localized: "share_scaled \(line) \(original)") },
            prep: labelPrep,
            cook: labelCook,
            total: labelTotal,
            ingredients: String(localized: "share_heading_ingredients"),
            instructions: String(localized: "share_heading_instructions")
        )
    }

    // Step timer notifications (#10)
    static func timerNotificationTitle(step: Int) -> String { String(localized: "timer_notification_title \(step)") }
}
