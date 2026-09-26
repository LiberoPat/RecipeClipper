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
    static var clipOffer: String { String(localized: "clip_offer") }
    static var clipItYourself: String { String(localized: "action_clip_it_yourself") }
    static var discard: String { String(localized: "action_discard") }
    static var remove: String { String(localized: "action_remove") }
    static var clipHint: String { String(localized: "clip_hint") }
    static var clipPickingPhoto: String { String(localized: "clip_picking_photo") }
    static var clipReview: String { String(localized: "clip_review") }
    static var clipBackToPage: String { String(localized: "clip_back_to_page") }
    static var clipPhotoFromPage: String { String(localized: "clip_photo_from_page") }
    static var clipNoPhoto: String { String(localized: "clip_no_photo") }
    static var clipOptional: String { String(localized: "clip_optional") }
    static func clipIngredientsHeading(_ n: Int) -> String { String(localized: "clip_ingredients_heading \(n)") }
    static func clipStepsHeading(_ n: Int) -> String { String(localized: "clip_steps_heading \(n)") }
    static var clipAddLine: String { String(localized: "clip_add_line") }
    static var clipAddStep: String { String(localized: "clip_add_step") }
    static func clipRemoveLine(_ n: Int) -> String { String(localized: "cd_clip_remove_line \(n)") }
    static var clipSave: String { String(localized: "clip_save") }
    static var updateFromSourceClipTitle: String { String(localized: "update_from_source_clip_title") }
    static var updateFromSourceClipBody: String { String(localized: "update_from_source_clip_body") }
    static var clippedByYou: String { String(localized: "clipped_by_you") }
    static func clippedByYou(on domain: String) -> String { String(localized: "clipped_by_you_on \(domain)") }
    static var extractedFromPage: String { String(localized: "extracted_from_page") }

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
        if s.recipesSkipped > 0, let free = s.freeLimit {
            parts.append(s.recipesSkipped == 1
                ? String(localized: "backup_skipped_free_one \(free)")
                : String(localized: "backup_skipped_free_other \(s.recipesSkipped) \(free)"))
        } else if s.recipesSkipped > 0 {
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

    // Share extension (the confirmation card shown over the app that shared)
    static var shareGettingRecipe: String { String(localized: "share_getting_recipe") }
    static var shareSaved: String { String(localized: "share_saved") }
    static var shareOpenToCook: String { String(localized: "share_open_to_cook") }
    static var shareNoLink: String { String(localized: "share_no_link") }
    static var shareUnlockHint: String { String(localized: "share_unlock_hint") }

    // The free tier and the unlock (#107). The number is the free limit.
    static var unlock: String { String(localized: "unlock") }
    static func unlockPrice(_ price: String) -> String { String(localized: "unlock_price \(price)") }
    static var unlimitedTitle: String { String(localized: "unlimited_title") }
    static var unlimitedBody: String { String(localized: "unlimited_body \(LibraryLimit.freeRecipes)") }
    static var unlimitedRestore: String { String(localized: "unlimited_restore") }
    static var unlimitedUnlocked: String { String(localized: "unlimited_unlocked") }
    static var unlimitedPending: String { String(localized: "unlimited_pending") }
    static var recipeNotKept: String { String(localized: "recipe_not_kept \(LibraryLimit.freeRecipes)") }
    static var libraryFullTitle: String { String(localized: "library_full_title") }
    static var libraryFullBody: String { String(localized: "library_full_body \(LibraryLimit.freeRecipes)") }
    static func recipesCount(_ n: Int, of max: Int) -> String {
        n > max ? String(localized: "recipes_count_over \(n) \(max)") : String(localized: "recipes_count \(n) \(max)")
    }

    /// The words for a purchase or restore that didn't simply unlock.
    static func unlockNotice(_ outcome: PurchaseOutcome) -> String {
        switch outcome {
        case .pending: String(localized: "unlimited_pending")
        case .nothingToRestore: String(localized: "unlock_nothing")
        case .unlocked: String(localized: "unlimited_unlocked")
        case .cancelled, .failed: String(localized: "unlock_failed")
        }
    }
    static var done: String { String(localized: "action_done") }
    static var close: String { String(localized: "action_close") }

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

    // Recipes (#102; it replaced History)
    static var recipesTitle: String { String(localized: "recipes_title") }
    static var recipesEmpty: String { String(localized: "recipes_empty") }
    static func recipesNoResults(_ query: String) -> String { String(localized: "recipes_no_results \(query)") }
    static var searchRecipes: String { String(localized: "label_search_recipes") }
    static var addRecipe: String { String(localized: "cd_add_recipe") }
    static var typeRecipe: String { String(localized: "action_type_recipe") }
    static var pasteLink: String { String(localized: "action_paste_link") }
    static var sortRecentlyViewed: String { String(localized: "sort_recently_viewed") }
    static var sortName: String { String(localized: "sort_name") }
    static var sortDateAdded: String { String(localized: "sort_date_added") }
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
    static var settingsSectionSteps: String { String(localized: "settings_section_steps") }
    static var amountsInStepsTitle: String { String(localized: "amounts_in_steps_title") }
    static var amountsInStepsDescription: String { String(localized: "amounts_in_steps_description") }

    // Settings
    static var navSettings: String { String(localized: "nav_settings") }
    static var settingsTitle: String { String(localized: "settings_title") }
    static var settingsSectionUnits: String { String(localized: "settings_section_units") }
    static var settingsSectionOvenTemperature: String { String(localized: "settings_section_oven_temperature") }
    static var settingsSectionAppearance: String { String(localized: "settings_section_appearance") }
    static func settingsVersion(_ version: String) -> String { String(localized: "settings_version \(version)") }

    // Chef mode (#100)
    static var chefModeTitle: String { String(localized: "chef_mode_title") }
    static var chefModeDescription: String { String(localized: "chef_mode_description") }
    static func chefModeLanguages(_ list: String) -> String { String(localized: "chef_mode_languages \(list)") }
    static var chefModeUnsupported: String { String(localized: "chef_mode_unsupported") }
    static var chefModeNotReady: String { String(localized: "chef_mode_not_ready") }
    static var chefModeNotEnabled: String { String(localized: "chef_mode_not_enabled") }
    static var stepShowAsWritten: String { String(localized: "step_show_as_written") }
    static var stepShowShort: String { String(localized: "step_show_short") }

    // Developer settings (#87): hidden, 7 taps on the version in Settings
    static var developerSettingsTitle: String { String(localized: "developer_settings_title") }
    static var developerSettingsIntro: String { String(localized: "developer_settings_intro") }
    static func developerFlagIssue(_ n: Int) -> String { String(localized: "developer_flag_issue \(n)") }
    static var developerFlagChanged: String { String(localized: "developer_flag_changed") }
    static var developerReset: String { String(localized: "developer_reset") }

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

    // Pantry expiry reminders (#52)
    static var settingsSectionPantry: String { String(localized: "settings_section_pantry") }
    static var expiryRemindersTitle: String { String(localized: "expiry_reminders_title") }
    static var expiryRemindersDescription: String { String(localized: "expiry_reminders_description") }
    static var expiryRemindersDenied: String { String(localized: "expiry_reminders_denied") }
    static var expiryNotificationTitle: String { String(localized: "expiry_notification_title") }

    /// "Milk and yogurt expire tomorrow.", or both sentences when some expire today.
    static func expiryNotificationBody(_ reminder: ExpiryReminder) -> String {
        func sentence(_ names: [String], one: (String) -> String, many: (String) -> String) -> String {
            let joined = ExpiryReminders.joinNames(names) { String(localized: "expiry_names_and \($0) \($1)") }
            return ExpiryReminders.capitalized(names.count == 1 ? one(joined) : many(joined), locale: .current)
        }
        let today = sentence(
            reminder.today,
            one: { String(localized: "expiry_one_today \($0)") }, many: { String(localized: "expiry_many_today \($0)") }
        )
        let tomorrow = sentence(
            reminder.tomorrow,
            one: { String(localized: "expiry_one_tomorrow \($0)") },
            many: { String(localized: "expiry_many_tomorrow \($0)") }
        )
        switch reminder.text {
        case .today: return today
        case .tomorrow: return tomorrow
        case .todayAndTomorrow: return today + " " + tomorrow
        }
    }

    // Bottom tabs (#47), behind the mealPlan flag (#87)
    static var tabRecipes: String { String(localized: "tab_recipes") }
    static var tabWeek: String { String(localized: "tab_week") }
    static var tabGroceries: String { String(localized: "tab_groceries") }
    static var tabPantry: String { String(localized: "tab_pantry") }

    // Week meal plan (#49)
    static var addToPlan: String { String(localized: "action_add_to_plan") }
    static func addToDay(_ day: String) -> String { String(localized: "action_add_to_day \(day)") }
    static var labelMeal: String { String(localized: "label_meal") }
    static func removedFromPlan(_ label: String) -> String { String(localized: "snackbar_removed_from_plan \(label)") }
    static var previousWeek: String { String(localized: "cd_previous_week") }
    static var nextWeek: String { String(localized: "cd_next_week") }
    static var thisWeek: String { String(localized: "action_this_week") }
    // Month view (#52)
    static var monthView: String { String(localized: "action_month_view") }
    static var weekView: String { String(localized: "action_week_view") }
    static var thisMonth: String { String(localized: "action_this_month") }
    static var previousMonth: String { String(localized: "cd_previous_month") }
    static var nextMonth: String { String(localized: "cd_next_month") }
    static var shareCalendar: String { String(localized: "action_share_calendar") }
    static func monthDayPlanned(_ day: String) -> String { String(localized: "cd_month_day_planned \(day)") }
    static var mealTypesTitle: String { String(localized: "meal_types_title") }
    static var today: String { String(localized: "label_today") }
    static var addMeal: String { String(localized: "action_add_meal") }
    static var move: String { String(localized: "action_move") }
    static var removeFromPlan: String { String(localized: "action_remove_from_plan") }
    static func addToDayTitle(_ day: String) -> String { String(localized: "add_to_day_title \(day)") }
    static var searchOrNote: String { String(localized: "label_search_or_note") }
    static func addAsNote(_ text: String) -> String { String(localized: "action_add_as_note \(text)") }
    static func moveMealTitle(_ label: String) -> String { String(localized: "move_meal_title \(label)") }
    static func moveToDay(_ day: String) -> String { String(localized: "action_move_to_day \(day)") }
    static var newMealType: String { String(localized: "action_new_meal_type") }
    static var renameMealTypeTitle: String { String(localized: "rename_meal_type_title") }
    static func deleteMealTypeTitle(_ name: String) -> String { String(localized: "delete_meal_type_title \(name)") }
    static var deleteMealTypeBody: String { String(localized: "delete_meal_type_body") }
    static var moveUp: String { String(localized: "action_move_up") }
    static var moveDown: String { String(localized: "action_move_down") }
    static var mealTypeName: String { String(localized: "label_meal_type_name") }
    // Weekly menus (#52)
    static var saveWeekAsMenu: String { String(localized: "action_save_week_as_menu") }
    static var applyMenu: String { String(localized: "action_apply_menu") }
    static var saveMenuTitle: String { String(localized: "save_menu_title") }
    static var renameMenuTitle: String { String(localized: "rename_menu_title") }
    static var menuNameHint: String { String(localized: "menu_name_hint") }
    static var applyMenuTitle: String { String(localized: "apply_menu_title") }
    static var menusEmpty: String { String(localized: "menus_empty") }
    static func deleteMenuTitle(_ name: String) -> String { String(localized: "delete_menu_title \(name)") }
    static var deleteMenuBody: String { String(localized: "delete_menu_body") }
    static func menuSaved(_ name: String) -> String { String(localized: "menu_saved \(name)") }
    static var menuSaveFailed: String { String(localized: "menu_save_failed") }
    static func menuMealCount(_ n: Int) -> String { String(localized: "menu_meal_count \(n)") }
    static func menuApplied(_ n: Int, _ name: String) -> String { String(localized: "menu_applied \(n) \(name)") }
    // Groceries (#50)
    static var add: String { String(localized: "action_add") }
    static var groceriesAddHint: String { String(localized: "groceries_add_hint") }
    static var groceriesEmpty: String { String(localized: "groceries_empty") }
    static var addToGroceries: String { String(localized: "action_add_to_groceries") }
    static var addToGroceriesTitle: String { String(localized: "add_to_groceries_title") }
    static var addWeekToGroceries: String { String(localized: "action_add_week_to_groceries") }
    static var groceriesWeekEmpty: String { String(localized: "groceries_week_empty") }
    static var clearChecked: String { String(localized: "action_clear_checked") }
    static var shareGroceries: String { String(localized: "action_share_groceries") }
    static var moveToAisle: String { String(localized: "action_move_to_aisle") }
    static var moveToAisleTitle: String { String(localized: "move_to_aisle_title") }
    static func groceryDeleted(_ label: String) -> String { String(localized: "snackbar_grocery_deleted \(label)") }
    static var checkedCleared: String { String(localized: "snackbar_checked_cleared") }

    static func aisle(_ aisle: Aisle) -> String {
        switch aisle {
        case .produce: return String(localized: "aisle_produce")
        case .meat: return String(localized: "aisle_meat")
        case .seafood: return String(localized: "aisle_seafood")
        case .dairy: return String(localized: "aisle_dairy")
        case .bakery: return String(localized: "aisle_bakery")
        case .baking: return String(localized: "aisle_baking")
        case .grains: return String(localized: "aisle_grains")
        case .canned: return String(localized: "aisle_canned")
        case .condiments: return String(localized: "aisle_condiments")
        case .spices: return String(localized: "aisle_spices")
        case .frozen: return String(localized: "aisle_frozen")
        case .snacks: return String(localized: "aisle_snacks")
        case .drinks: return String(localized: "aisle_drinks")
        case .other: return String(localized: "aisle_other")
        }
    }

    // Pantry, What I need and grocery check-off (#51)
    static var pantryAddHint: String { String(localized: "pantry_add_hint") }
    static var pantrySearchHint: String { String(localized: "pantry_search_hint") }
    static var pantryEmpty: String { String(localized: "pantry_empty") }
    static func pantryNoResults(_ query: String) -> String { String(localized: "pantry_no_results \(query)") }
    static var pantrySortAisle: String { String(localized: "pantry_sort_aisle") }
    static var pantrySortExpiry: String { String(localized: "pantry_sort_expiry") }
    static var pantryInStock: String { String(localized: "pantry_in_stock") }
    static var pantryOut: String { String(localized: "pantry_out") }
    static var pantryAlwaysHave: String { String(localized: "pantry_always_have") }
    static var pantryAlwaysHaveDetail: String { String(localized: "pantry_always_have_detail") }
    static var pantryExpired: String { String(localized: "pantry_expired") }
    static func pantryUseBy(_ date: String) -> String { String(localized: "pantry_use_by \(date)") }
    static func pantryBought(_ date: String) -> String { String(localized: "pantry_bought \(date)") }
    static var pantryEditTitle: String { String(localized: "pantry_edit_title") }
    static var pantryLabelName: String { String(localized: "pantry_label_name") }
    static var pantryLabelQuantity: String { String(localized: "pantry_label_quantity") }
    static var pantryLabelExpiry: String { String(localized: "pantry_label_expiry") }
    static var pantryNoDate: String { String(localized: "pantry_no_date") }
    static var setDate: String { String(localized: "action_set_date") }
    static var clearDate: String { String(localized: "action_clear_date") }
    static func pantryOutSnackbar(_ name: String) -> String { String(localized: "snackbar_pantry_out \(name)") }
    static func addedToGroceries(_ name: String) -> String { String(localized: "snackbar_added_to_groceries \(name)") }
    static func pantryDeleted(_ name: String) -> String { String(localized: "snackbar_pantry_deleted \(name)") }
    static var whatINeedTitle: String { String(localized: "what_i_need_title") }
    static var whatINeedBuy: String { String(localized: "what_i_need_buy") }
    static var whatINeedHave: String { String(localized: "what_i_need_have") }
    static var whatINeedNote: String { String(localized: "what_i_need_note") }
    static var whatINeedNothingToBuy: String { String(localized: "what_i_need_nothing_to_buy") }
    static func whatINeedYouHave(_ name: String) -> String { String(localized: "what_i_need_you_have \(name)") }
    static var whatINeedAdded: String { String(localized: "what_i_need_added") }
    static var addToPantry: String { String(localized: "action_add_to_pantry") }
    static func offerPantry(_ name: String) -> String { String(localized: "snackbar_offer_pantry \(name)") }
    static func pantryRestocked(_ name: String) -> String { String(localized: "snackbar_pantry_restocked \(name)") }

    // The first-run tour (#151)
    static var welcomeSkip: String { String(localized: "welcome_skip") }
    static var welcomeNext: String { String(localized: "welcome_next") }
    static var welcomeBack: String { String(localized: "welcome_back") }
    static func welcomePage(_ n: Int, of total: Int) -> String { String(localized: "welcome_page \(n) \(total)") }
    static var welcomeTrySample: String { String(localized: "welcome_try_sample") }
    static var welcomeStart: String { String(localized: "welcome_start") }
    static var welcomeAppTitle: String { String(localized: "welcome_app_title") }
    static var welcomeAppBody: String { String(localized: "welcome_app_body") }
    static var welcomeAppOffline: String { String(localized: "welcome_app_offline") }
    static var welcomeClipTitle: String { String(localized: "welcome_clip_title") }
    /// iOS's own wording: the share extension saves the recipe without opening the app.
    static var welcomeClipShare: String { String(localized: "welcome_clip_share_ios") }
    static var welcomeClipPaste: String { String(localized: "welcome_clip_paste") }
    static var welcomeClipType: String { String(localized: "welcome_clip_type") }
    static var welcomeDailyTitle: String { String(localized: "welcome_daily_title") }
    static var welcomeDailyServings: String { String(localized: "welcome_daily_servings") }
    static var welcomeDailyLists: String { String(localized: "welcome_daily_lists") }
    static var welcomeDailyCook: String { String(localized: "welcome_daily_cook") }
    static var welcomeDailyChef: String { String(localized: "welcome_daily_chef") }
    static var welcomeWeeklyTitle: String { String(localized: "welcome_weekly_title") }
    static var welcomeWeeklyPlan: String { String(localized: "welcome_weekly_plan") }
    static var welcomeWeeklyNeed: String { String(localized: "welcome_weekly_need") }
    static var welcomeWeeklyGroceries: String { String(localized: "welcome_weekly_groceries") }
    static var welcomeWeeklyPantry: String { String(localized: "welcome_weekly_pantry") }
    static func tip(_ tip: Tip) -> String {
        switch tip {
        case .recipe: String(localized: "tip_recipe")
        case .cookMode: String(localized: "tip_cook_mode")
        case .week: String(localized: "tip_week")
        case .groceries: String(localized: "tip_groceries")
        case .pantry: String(localized: "tip_pantry")
        }
    }
    static var dismissTip: String { String(localized: "cd_dismiss_tip") }
    static var settingsSectionHelp: String { String(localized: "settings_section_help") }
    static var settingsShowTour: String { String(localized: "settings_show_tour") }
    static var settingsShowTourDescription: String { String(localized: "settings_show_tour_description") }
}
