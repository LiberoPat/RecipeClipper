import Combine
import Foundation

/// The real, SQLite-backed RecipeRepository (Android's DefaultRecipeRepository).
///
/// The contract's methods don't throw, so a database failure is logged and surfaced the way
/// the contract allows: `.error(.saveFailed)` from an import, nil from `open` / `delete`, and
/// nothing from `setChecked` / `setNotes` / `setCookProgress` / `setServingsTarget` / `restore`.
final class DefaultRecipeRepository: RecipeRepository {
    private let db: AppDatabase
    private let source: RecipeSource
    private let clock: Clock
    private let sleep: RetrySleep
    private let renderedPages: RenderedPageSource
    private let renderTimeout: Duration
    private let library: LibraryLimitSource
    private let photos: PhotoStore?
    private let extractor: PageRecipeExtractor
    /// The `llmExtraction` flag (#103), read at each import; the share extension leaves it off.
    private let extractionOn: () -> Bool

    /// How a failed fetch waits before its single retry. Injected so a test doesn't really wait;
    /// throws on cancellation, as `Task.sleep` does.
    typealias RetrySleep = (Duration) async throws -> Void

    /// How long to wait before the single automatic retry: long enough for a momentary block to
    /// lift, short enough that the spinner doesn't feel stuck. Android's RETRY_PAUSE_MS.
    static let retryPause: Duration = .seconds(2)

    /// The cap on the off-screen browser fallback, load and settle together. It comes on top of
    /// the direct fetch and its retry, so a page that never settles can't hold the spinner much
    /// longer than they did. Android's RENDER_TIMEOUT_MS.
    static let renderTimeout: Duration = .seconds(20)

    init(
        db: AppDatabase,
        source: RecipeSource,
        clock: Clock,
        sleep: @escaping RetrySleep = { try await Task.sleep(for: $0) },
        renderedPages: RenderedPageSource = NoRenderedPageSource(),
        renderTimeout: Duration = DefaultRecipeRepository.renderTimeout,
        library: LibraryLimitSource = FixedLibraryLimit(),
        extractor: PageRecipeExtractor = NoPageRecipeExtractor(),
        extractionOn: @escaping () -> Bool = { false },
        photos: PhotoStore? = nil
    ) {
        self.photos = photos
        self.library = library
        self.extractor = extractor
        self.extractionOn = extractionOn
        self.db = db
        self.source = source
        self.clock = clock
        self.sleep = sleep
        self.renderedPages = renderedPages
        self.renderTimeout = renderTimeout
    }

    func importFromUrl(_ sharedUrl: String, renderedPage: String?) async -> ParseResult {
        // Saved under the cleaned link, so tracking tags can't make one recipe into two.
        let url = UrlCleaner.clean(sharedUrl)

        // The user's version is never refreshed by a re-share (#29): open it without a fetch.
        do {
            let viewedAt = clock.now()
            let limit = library.current()
            let usersVersion = try await db.write { conn -> RecipeRecord? in
                let dao = RecipeDao(db: conn)
                guard var row = try dao.findByUrl(url), !ContentOrigin.isSources(row.contentOrigin)
                else { return nil }
                row.lastViewedAt = viewedAt
                _ = try dao.upsert(row, limit: limit, today: PlanDays.today(millis: viewedAt)) // a view, and the cull
                return row
            }
            if let usersVersion { return .success(usersVersion.toDomain()) }
        } catch {
            dataLog.error("find user's version failed: \(String(describing: error), privacy: .public)")
        }

        let parsed = await fetchWithFallbacks(url, renderedPage: renderedPage)

        // RecipeSource.fetch can't throw, so cancellation can't propagate as it does on
        // Android. Match Android's outcome instead: a cancelled import writes nothing (not
        // even a touch). The cancelled caller discards whatever is returned.
        if Task.isCancelled { return parsed }
        let now = clock.now()

        switch parsed {
        case .notKept:
            return parsed // a fetch never answers this: only a save does
        case .success(var recipe):
            // Pin the key to the cleaned link we looked up by, so the offline fallback below
            // always finds what was saved here. (The parser already sets this; belt and braces.)
            recipe.sourceUrl = url
            return await save(recipe, now: now, what: "import save")

        case .error:
            // Offline, blocked or anything else: anything opened once still opens.
            let cached = try? await db.write { conn -> RecipeRecord? in
                let dao = RecipeDao(db: conn)
                guard var row = try dao.findByUrl(url) else { return nil }
                try dao.touch(row.id, now: now)
                row.lastViewedAt = now
                return row
            }
            guard let cached = cached ?? nil else { return parsed }
            return .success(cached.toDomain())
        }
    }

    /// With a rendered page already in hand (Safari's share extension preprocessing, #35),
    /// parses it directly and skips the fetch entirely. Only when that page holds no recipe
    /// does the ordinary fetch — with its retry and rendered-browser fallback — run, exactly as
    /// if `renderedPage` had been nil.
    ///
    /// Otherwise: fetches, retries once after a pause if that often clears on its own, then
    /// loads the page once in an off-screen browser if it is still blocked or has no recipe
    /// data.
    private func fetchWithFallbacks(_ url: String, renderedPage: String?) async -> ParseResult {
        if let renderedPage {
            let fromPage = BlogRecipeSource.parse(html: renderedPage, url: url)
            if case .success = fromPage { return fromPage }
        }

        var fetched = await source.fetchPage(url: url)

        // A block or a network blip often clears on its own: wait, then fetch once more. Offline
        // fails straight away, a timeout isn't repeated (a dead Wi-Fi costs one timeout, not
        // two), and a page that loaded with no recipe is never retried.
        if case .error(let error) = fetched.result, error.shouldAutoRetry, !Task.isCancelled {
            do {
                try await sleep(Self.retryPause)
            } catch {
                return fetched.result // cancelled during the pause: the caller writes nothing
            }
            if !Task.isCancelled { fetched = await source.fetchPage(url: url) }
        }
        let parsed = fetched.result

        // Still blocked, or a page with no recipe data: load it once in an off-screen browser
        // and run what it renders through the same parsers. Never after offline or a timeout.
        // A rendered page with no recipe, or one that doesn't load, leaves the cause standing.
        guard case .error(let error) = parsed, error.triesRenderedPage, !Task.isCancelled else { return parsed }
        var rendered: FetchedPage?
        if let html = await renderCapped(url), !Task.isCancelled {
            rendered = BlogRecipeSource.parsePage(html: html, url: url)
            if let rendered, case .success = rendered.result { return rendered.result }
        }
        // Last, only for a page that loaded with no recipe data: the on-device model may pick
        // one out of its text (the rendered page's if there is one; #103).
        guard error == .noRecipeFound, !Task.isCancelled,
              let page = rendered?.page ?? fetched.page ?? renderedPage.map({ PageTextReader.read(html: $0, url: url) })
        else { return parsed }
        return await extractFromPage(page, url: url) ?? parsed
    }

    /// A recipe the on-device model picked out of `page`'s text (#103), behind the
    /// `llmExtraction` flag: the part most likely to hold it (`RecipeTextWindow`), then only what
    /// `PageRecipe.recipe` finds on the page as written. Nil, and the page stays
    /// `.noRecipeFound`, on a phone or in a language the model can't read, or when too little of
    /// what it picked is on the page. Android's `extractFromPage`.
    private func extractFromPage(_ page: PageText, url: String) async -> ParseResult? {
        guard extractionOn() else { return nil }
        let language = PageRecipe.language(page)
        guard let chars = await extractor.windowChars(language: language),
              let window = RecipeTextWindow.window(page, maxChars: chars),
              let picked = await extractor.extract(window, language: language), !Task.isCancelled,
              let recipe = PageRecipe.recipe(window: window, picked: picked, page: page, url: url)
        else { return nil }
        return .success(recipe)
    }

    func updateFromSource(id: Int64) async -> ParseResult {
        guard let existing = (try? await db.read { conn in try RecipeDao(db: conn).get(id) }) ?? nil
        else { return .error(.notSaved) }
        guard existing.toDomain().canUpdateFromSource else { return .error(.nothingToShow) }
        let parsed = await fetchWithFallbacks(existing.sourceUrl, renderedPage: nil)
        if Task.isCancelled { return parsed }
        guard case .success(var recipe) = parsed else { return parsed }
        // Filed under the saved link, whatever the parse reports, so it lands on this row.
        recipe.sourceUrl = existing.sourceUrl
        let fresh = recipe.toRecord(viewedAt: clock.now())
        let limit = library.current()
        do {
            let saved = try await db.write { conn -> RecipeRecord? in
                let dao = RecipeDao(db: conn)
                _ = try dao.upsert(
                    fresh, limit: limit, replaceUsersVersion: true,
                    today: PlanDays.today(millis: fresh.lastViewedAt)
                )
                return try dao.get(id)
            }
            guard let saved else { return .error(.saveFailed) }
            return .success(saved.toDomain())
        } catch {
            dataLog.error("updateFromSource save failed: \(String(describing: error), privacy: .public)")
            return .error(.saveFailed)
        }
    }

    func saveEdit(id: Int64, draft: RecipeDraft) async -> Recipe? {
        guard draft.isValid else { return nil }
        let now = clock.now()
        do {
            return try await db.write { conn -> Recipe? in
                let dao = RecipeDao(db: conn)
                guard let existing = try dao.get(id)?.toDomain() else { return nil }
                let edited = draft.apply(to: existing).toRecord(viewedAt: existing.lastViewedAt)
                guard try dao.saveEdit(id, edited: edited, origin: existing.origin.afterEdit().rawValue, editedAt: now)
                else { return nil }
                return try dao.get(id)?.toDomain()
            }
        } catch {
            dataLog.error("saveEdit failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func addManual(draft: RecipeDraft) async -> Recipe? {
        guard draft.isValid else { return nil }
        let now = clock.now()
        var recipe = draft.apply(to: Recipe(
            name: "", image: nil, ingredients: [], instructions: [],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil,
            sourceUrl: ManualRecipe.newSourceUrl(newUid())
        ))
        recipe.origin = .manual
        recipe.editedAt = now
        let record = recipe.toRecord(viewedAt: now)
        let limit = library.current()
        do {
            return try await db.write { conn -> Recipe? in
                let dao = RecipeDao(db: conn)
                let id = try dao.upsert(record, limit: limit, today: PlanDays.today(millis: now))
                if id == RecipeDao.notKept { return recipe }
                return try dao.get(id)?.toDomain()
            }
        } catch {
            dataLog.error("addManual failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    /// The rendered page, or nil once `renderTimeout` runs out (which cancels the render).
    private func renderCapped(_ url: String) async -> String? {
        let renderedPages = renderedPages
        let renderTimeout = renderTimeout
        return await withTaskGroup(of: String?.self) { group in
            group.addTask { await renderedPages.render(url: url) }
            group.addTask {
                try? await Task.sleep(for: renderTimeout)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    func saveClip(_ recipe: Recipe) async -> ParseResult {
        // CLIPPED, so a re-share opens the clip rather than fetching the page again (#29's
        // rule); a clip replaces whatever the row held, as "Update from source" does.
        var clip = recipe
        clip.sourceUrl = UrlCleaner.clean(recipe.sourceUrl)
        clip.origin = .clipped
        clip.editedAt = nil
        return await save(clip, now: clock.now(), replaceUsersVersion: true, what: "clip save")
    }

    func keep(_ recipe: Recipe) async -> ParseResult {
        await save(recipe, now: clock.now(), what: "keep")
    }

    /// Saves `recipe` as a view at `now`, under the library's limit (#107): the saved row, or
    /// `.notKept` when the full library had no room.
    private func save(_ recipe: Recipe, now: Int64, replaceUsersVersion: Bool = false, what: String) async -> ParseResult {
        let limit = library.current()
        do {
            let saved = try await db.write { conn -> RecipeRecord?? in
                let dao = RecipeDao(db: conn)
                let id = try dao.upsert(
                    recipe.toRecord(viewedAt: now), limit: limit, replaceUsersVersion: replaceUsersVersion,
                    today: PlanDays.today(millis: now)
                )
                if id == RecipeDao.notKept { return .some(nil) }
                return .some(try dao.get(id))
            }
            switch saved {
            case .some(.some(let row)): return .success(row.toDomain())
            case .some(.none):
                var shown = recipe
                shown.id = 0
                return .notKept(shown)
            case .none: return .error(.saveFailed)
            }
        } catch {
            dataLog.error("\(what, privacy: .public) failed: \(String(describing: error), privacy: .public)")
            return .error(.saveFailed)
        }
    }

    func open(id: Int64) async -> Recipe? {
        let now = clock.now()
        do {
            let row = try await db.write { conn -> RecipeRecord? in
                let dao = RecipeDao(db: conn)
                guard var row = try dao.get(id) else { return nil }
                try dao.touch(id, now: now)
                row.lastViewedAt = now
                return row
            }
            return row?.toDomain()
        } catch {
            dataLog.error("open failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func setChecked(id: Int64, checked: Set<Int>) async {
        do {
            try await db.write { conn in try RecipeDao(db: conn).setChecked(id, checked: checked) }
        } catch {
            dataLog.error("setChecked failed: \(String(describing: error), privacy: .public)")
        }
    }

    func setNotes(id: Int64, notes: String) async {
        let stored: String? = notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : notes
        do {
            try await db.write { conn in try RecipeDao(db: conn).setNotes(id, notes: stored) }
        } catch {
            dataLog.error("setNotes failed: \(String(describing: error), privacy: .public)")
        }
    }

    func setCookProgress(id: Int64, progress: CookProgress) async {
        let json = CookStateJSON.encode(progress)
        do {
            try await db.write { conn in try RecipeDao(db: conn).setCookState(id, cookState: json) }
        } catch {
            dataLog.error("setCookProgress failed: \(String(describing: error), privacy: .public)")
        }
    }

    func setServingsTarget(id: Int64, target: Int?) async {
        do {
            try await db.write { conn in try RecipeDao(db: conn).setServingsTarget(id, target: target) }
        } catch {
            dataLog.error("setServingsTarget failed: \(String(describing: error), privacy: .public)")
        }
    }

    func delete(id: Int64) async -> DeletedRecipe? {
        do {
            return try await db.write { conn -> DeletedRecipe? in
                let dao = RecipeDao(db: conn)
                guard let row = try dao.get(id) else { return nil }
                // Read before deleting: the cascade takes them with the row.
                let memberships = try dao.crossRefsFor(id)
                let planEntries = try dao.planEntriesFor(id)
                let menuEntries = try dao.menuEntriesFor(id)
                let cookedPhotos = try CookedPhotoDao(db: conn).photosFor(id)
                try dao.delete(id)
                return DeletedRecipe(
                    recipe: row.toDomain(), memberships: memberships, uid: row.uid, planEntries: planEntries,
                    menuEntries: menuEntries, cookedPhotos: cookedPhotos
                )
            }
        } catch {
            dataLog.error("delete failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func restore(_ deleted: DeletedRecipe) async {
        do {
            var record = deleted.recipe.toRecord(viewedAt: deleted.recipe.lastViewedAt)
            if let uid = deleted.uid { record.uid = uid }
            try await db.write { [record] conn in
                try RecipeDao(db: conn).restore(record, crossRefs: deleted.memberships, planEntries: deleted.planEntries,
                    menuEntries: deleted.menuEntries, cookedPhotos: deleted.cookedPhotos
                )
            }
        } catch {
            dataLog.error("restore failed: \(String(describing: error), privacy: .public)")
        }
    }

    // The photos belong to the recipe (#116): once its delete stands, their files go too.
    func forget(_ deleted: DeletedRecipe) async {
        guard let photos, !deleted.cookedPhotos.isEmpty else { return }
        await photos.delete(deleted.cookedPhotos.map(\.fileName))
    }

    func observeHistory(query: String) -> AnyPublisher<[RecipeSummary], Never> {
        db.observe { conn in try RecipeDao(db: conn).history(query: query).map { $0.toDomain() } }
    }

    func observeRecent(limit: Int) -> AnyPublisher<[RecipeSummary], Never> {
        db.observe { conn in try RecipeDao(db: conn).recent(limit: limit).map { $0.toDomain() } }
    }

    /// Every recipe but the tour's sample (#151), which never takes a free-tier place.
    func observeCount() -> AnyPublisher<Int, Never> {
        db.observe { conn in try RecipeDao(db: conn).count() }
    }

    func sampleId() async -> Int64? {
        do {
            return try await db.read { conn in try RecipeDao(db: conn).findByUrl(SampleRecipe.sourceUrl)?.id }
        } catch {
            dataLog.error("sampleId failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func addSample(_ recipe: Recipe) async -> Int64? {
        var sample = recipe
        sample.sourceUrl = SampleRecipe.sourceUrl
        sample.origin = .manual
        let record = sample.toRecord(viewedAt: clock.now())
        do {
            // Unlimited: the sample counts toward no limit, so adding it never removes a recipe.
            let id = try await db.write { conn in try RecipeDao(db: conn).upsert(record, limit: .unlimited) }
            return id == RecipeDao.notKept ? nil : id
        } catch {
            dataLog.error("addSample failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }
}
