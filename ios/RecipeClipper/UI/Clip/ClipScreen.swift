import SwiftUI

/// "Clip it yourself" (#37), Android's ClipScreen: the page with no recipe data, live, with a
/// toolbar for saying where the selected text goes, and a Review pane before saving. The page
/// stays in the hierarchy under Review so going back to it keeps its scroll position and marks.
struct ClipScreen: View {
    let vm: ClipViewModel
    var fixtureHTML: String? = nil
    let onSaved: (Int64) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let state = vm.uiState
        let waiting = state.check == .waiting
        VStack(spacing: 0) {
            if state.readBlocked && !state.reviewing {
                note(Strings.clipRedditBlockedNote)
            }
            // Cloudflare's check (#220): what to do, then, past it with no recipe, why this is open.
            if waiting {
                note(Strings.clipHumanCheckNote)
            } else if state.check == .noRecipe && !state.reviewing {
                note(Strings.clipHumanCheckNoRecipeNote)
            }
            ZStack {
                ClipWebPage(
                    url: state.pageUrl,
                    fixtureHTML: fixtureHTML,
                    // The new mark, and taps that select, go to the view showing.
                    syncState: Self.syncJson(
                        state.draft, newMarkId: state.showingText ? nil : state.newMarkId,
                        armed: state.showingText ? nil : state.armedText, clear: state.clearSelection
                    ),
                    pickingPhoto: state.pickingPhoto,
                    onEvent: onPageEvent,
                    readsPage: waiting,
                    readText: state.readingText
                )
                // The Text view (#213) lies over the page, which stays loaded (its scroll and
                // marks) under it; hidden, it keeps its own.
                if let text = state.pageText {
                    let html = RedditTextPage.html(
                        text, commentsHeading: Strings.clipTextComments, loadedNote: Strings.clipTextLoadedNote,
                        author: Strings.clipTextAuthor
                    )
                    ClipWebPage(
                        url: state.pageUrl,
                        textHTML: html,
                        syncState: Self.syncJson(
                            state.draft, newMarkId: state.showingText ? state.newMarkId : nil,
                            armed: state.showingText ? state.armedText : nil, clear: state.clearSelection
                        ),
                        pickingPhoto: false,
                        onEvent: onPageEvent
                    )
                    .id(html)
                    .opacity(state.showingText ? 1 : 0)
                    .allowsHitTesting(state.showingText)
                    .accessibilityHidden(!state.showingText)
                    .accessibilityIdentifier("clip.text")
                }
                if state.reviewing {
                    ClipReviewPane(vm: vm)
                }
            }
            // Nothing to clip while the page is Cloudflare's check (#220).
            if !state.reviewing && !waiting {
                ClipToolbar(vm: vm)
            }
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        // Full screen under the tab shell (#47): the page needs the room, and a clip isn't a
        // tab of its own. Set here rather than on the route in RootView: wrapping the
        // ScreenHost with it there stopped the page's taps from reaching the ViewModel.
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                if state.reviewing {
                    Button(Strings.clipBackToPage, action: vm.onBackToPage)
                } else {
                    // Cancel keeps the draft for the session: reopening this page restores it.
                    Button(Strings.cancel) { dismiss() }
                }
            }
            ToolbarItem(placement: .principal) {
                Text(SourceDomain.of(state.pageUrl) ?? "")
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(1)
            }
            // The switch between the page and the Text view (#213).
            ToolbarItem(placement: .topBarTrailing) {
                if state.offersText && !state.reviewing && !waiting {
                    if state.showingText {
                        Button(Strings.clipShowPage, action: vm.onShowPage)
                    } else {
                        Button(Strings.clipShowText, action: vm.onShowText)
                    }
                }
            }
            // Done opens Review; in Review it is Save. Either is always tappable (bar Cloudflare's
            // check and a save under way): what's missing is said, never only greyed out.
            ToolbarItem(placement: .topBarTrailing) {
                if state.reviewing {
                    Button(Strings.save, action: vm.onSave).disabled(state.saving)
                } else {
                    Button(Strings.done, action: vm.onReview).disabled(waiting)
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let notice = state.notice {
                noticeView(notice)
                    .padding(.horizontal, 12)
                    .padding(.bottom, state.reviewing ? 12 : 150)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: state.notice)
        // Each notice shows for a while, then goes; a newer one restarts the wait.
        .task(id: state.notice?.serial) {
            guard let serial = state.notice?.serial else { return }
            do { try await Task.sleep(for: SnackbarTimeout.duration) } catch { return }
            vm.onNoticeShown(serial)
        }
        .onChange(of: state.savedRecipeId) { _, id in
            if let id { onSaved(id) }
        }
        .onChange(of: state.check) { _, check in
            if check == .noRecipe {
                AccessibilityNotification.Announcement(Strings.clipHumanCheckNoRecipeNote).post()
            }
        }
        // The cook asked for the recipe, not for this screen (#213): VoiceOver hears why.
        .task {
            if vm.uiState.readBlocked {
                AccessibilityNotification.Announcement(Strings.clipRedditBlockedNote).post()
            } else if vm.uiState.check == .waiting {
                AccessibilityNotification.Announcement(Strings.clipHumanCheckNote).post()
            }
        }
        .libraryFullAlert(
            isPresented: Binding(get: { vm.uiState.libraryFull }, set: { if !$0 { vm.onLibraryFullDismiss() } }),
            onUnlock: vm.onUnlock
        )
    }

    private func onPageEvent(_ event: ClipPageEvent) {
        switch event {
        case .selection(let text): vm.onSelectionChanged(text)
        case .tagTapped(let id): vm.onTagTapped(id)
        case .imageTapped(let src): vm.onImageTapped(src)
        case .noImage: vm.onNoImageTapped()
        case .pageLoaded(let html): vm.onPageLoaded(html)
        case .pageText(let html): vm.onPageText(html)
        }
    }

    /// Why the clip opened by itself: Reddit wouldn't let the app read the post (#213), or the
    /// site wants the cook to pass Cloudflare's check (#220), or did and has no recipe data.
    private func note(_ text: String) -> some View {
        VStack(spacing: 0) {
            Text(text)
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.muted)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            Rectangle().fill(Palette.hairline).frame(height: 1)
        }
    }

    @ViewBuilder
    private func noticeView(_ notice: ClipNotice) -> some View {
        let text = Strings.clipMessage(notice.message)
        switch notice.message {
        case .removed:
            Snackbar(message: text, actionLabel: Strings.undo) {
                vm.onUndo()
                vm.onNoticeShown(notice.serial)
            }
        case .draftRestored:
            Snackbar(message: text, actionLabel: Strings.discard) {
                vm.onDiscardDraft()
                vm.onNoticeShown(notice.serial)
            }
        case .saveFailed, .unlock:
            Snackbar(message: text, actionLabel: Strings.cancel) { vm.onNoticeShown(notice.serial) }
        case .photoUnreadable, .missing, .textUnreadable:
            // Nothing to act on: the words say what to do.
            Snackbar(message: text, actionLabel: nil)
        }
    }

    /// The argument to `RC.sync`: the marks this draft holds, each add with its own tag
    /// ("Ingredients · 3"), the field the page's taps select for (`armed`, #237) and the clear
    /// count.
    static func syncJson(_ draft: ClipDraft, newMarkId: String?, armed: ClipField? = nil, clear: Int = 0) -> String {
        var marks: [[String: String]] = []
        for mark in draft.marks where mark.field != .photo {
            let label = mark.field.replaces
                ? Strings.clipField(mark.field) : Strings.clipTagCount(Strings.clipField(mark.field), mark.lines.count)
            marks.append(["id": mark.id, "field": mark.field.rawValue, "label": label])
        }
        var json: [String: Any] = ["marks": marks, "armed": armed?.rawValue ?? NSNull(), "clear": clear]
        if let newMarkId { json["newId"] = newMarkId }
        if let photo = draft.photo {
            let id = draft.marks.last { $0.field == .photo }?.id ?? ""
            json["photo"] = ["src": photo, "id": id, "label": Strings.clipField(.photo)]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: json),
              let string = String(data: data, encoding: .utf8)
        else { return "{}" }
        return string
    }
}

/// Field first (#237): the hint bar (what to do now) over one button per field. A field button
/// arms its field (filled while armed) and shows what it holds (a check and a count).
private struct ClipToolbar: View {
    let vm: ClipViewModel

    var body: some View {
        let state = vm.uiState
        VStack(alignment: .leading, spacing: 6) {
            Hairline()
            hintBar(state)
                .padding(.horizontal, 16)
                .frame(minHeight: 40)
                .accessibilityElement(children: .contain)
            HStack(spacing: 6) {
                ForEach(ClipField.allCases, id: \.self) { field in
                    fieldButton(field, armed: state.armed == field, count: state.draft.count(field))
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .background(Palette.background)
    }

    @ViewBuilder
    private func hintBar(_ state: ClipUiState) -> some View {
        switch state.hint {
        case .pickPhoto:
            // The photo is optional: Skip leaves this step without tapping the page.
            HStack {
                Text(Strings.clipPickingPhoto)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.accentText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(Strings.clipSkipPhoto, action: vm.onSkipPhoto)
                    .buttonStyle(TextActionStyle())
            }
        case .select(let field):
            Text(Strings.clipSelect(field))
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.accentText)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .confirm(let field, let lines):
            VStack(alignment: .leading, spacing: 4) {
                if let line = state.selection.first {
                    Text(line).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted).lineLimit(1)
                }
                HStack {
                    Button(Strings.clipConfirm(field, lines), action: vm.onConfirm)
                        .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                        .accessibilityIdentifier("clip.confirm")
                    Button(Strings.clear, action: vm.onClearSelection)
                        .buttonStyle(TextActionStyle())
                }
            }
        case .selected(let lines):
            VStack(alignment: .leading, spacing: 2) {
                Text(Strings.clipLinesSelected(lines))
                    .textStyle(Typography.labelLarge)
                    .foregroundStyle(Palette.muted)
                if let line = state.selection.first {
                    Text(line).textStyle(Typography.bodySmall).lineLimit(1)
                }
            }
        case .next(let added, let next):
            HStack(spacing: 10) {
                if added?.field == .photo, let photo = state.draft.photo {
                    CachedAsyncImage(url: URL(string: photo)) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Palette.surfaceContainer
                    }
                    .frame(width: 36, height: 36)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .accessibilityHidden(true)
                }
                Text([added.map(Strings.clipAdded), Strings.clipNext(next, empty: state.draft.isEmpty)]
                    .compactMap { $0 }.joined(separator: " "))
                    .textStyle(Typography.bodyMedium)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if added != nil {
                    Button(Strings.undo, action: vm.onUndo).buttonStyle(TextActionStyle())
                }
            }
        }
    }

    private func fieldButton(_ field: ClipField, armed: Bool, count: Int) -> some View {
        Button { vm.onFieldButton(field) } label: {
            VStack(spacing: 2) {
                Text(Strings.clipField(field))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                // What the field holds: a check, with the count for ingredients and steps.
                Text(Strings.clipFilled(field, count))
                    .textStyle(Typography.labelSmall)
                    .accessibilityHidden(count == 0)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(ClipFieldButtonStyle(selected: armed))
        .accessibilityAddTraits(armed ? .isSelected : [])
        // The page's own tags carry the same words; tests tell the toolbar's apart by this.
        .accessibilityIdentifier("clip.field.\(field.rawValue)")
    }
}
private struct ClipFieldButtonStyle: ButtonStyle {
    let selected: Bool
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .textStyle(Typography.labelMedium)
            .foregroundStyle(selected ? Palette.onPrimary : Palette.onBackground)
            .padding(.horizontal, 4)
            .frame(minHeight: 40)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(selected ? Palette.primary : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(selected ? Palette.primary : Palette.outline, lineWidth: 1)
            )
            .contentShape(Rectangle())
            .opacity(isEnabled ? (configuration.isPressed ? 0.6 : 1) : 0.4)
    }
}

/// Review before saving: the photo, name, the optional typed Serves and Total time, and every
/// line, editable.
private struct ClipReviewPane: View {
    let vm: ClipViewModel

    var body: some View {
        let state = vm.uiState
        let draft = state.draft
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(Strings.clipReview).textStyle(Typography.headlineMedium)

                if let photo = draft.photo {
                    HStack(spacing: 12) {
                        CachedAsyncImage(url: URL(string: photo)) { image in
                            image.resizable().scaledToFill()
                        } placeholder: {
                            Palette.surfaceContainer
                        }
                        .frame(width: 64, height: 64)
                        .clipped()
                        Text(Strings.clipPhotoFromPage).textStyle(Typography.bodyMedium)
                        Spacer()
                        Button(Strings.remove, action: vm.onRemovePhoto).buttonStyle(TextActionStyle())
                    }
                } else {
                    Text(Strings.clipNoPhoto).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                }

                labelled(Strings.clipField(.name)) {
                    field(Strings.clipField(.name), text: draft.name, onChange: vm.onNameChange)
                }
                labelled(Strings.serves) {
                    field(Strings.clipOptional, text: draft.serves, onChange: vm.onServesChange)
                        .accessibilityLabel(Strings.serves)
                }
                labelled(Strings.editLabelTotal) {
                    field(Strings.clipOptional, text: draft.totalTime, onChange: vm.onTotalTimeChange)
                        .accessibilityLabel(Strings.editLabelTotal)
                }

                lines(.ingredients, heading: Strings.clipIngredientsHeading(draft.count(.ingredients)), add: Strings.clipAddLine)
                lines(.steps, heading: Strings.clipStepsHeading(draft.count(.steps)), add: Strings.clipAddStep)

                Button(Strings.clipSave, action: vm.onSave)
                    .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                    .disabled(state.saving)
                    .padding(.vertical, 12)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Palette.background)
    }

    private func labelled<Content: View>(_ label: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).textStyle(Typography.labelMedium).foregroundStyle(Palette.muted)
            content()
        }
    }

    private func field(_ placeholder: String, text: String, onChange: @escaping (String) -> Void) -> some View {
        TextField(placeholder, text: Binding(get: { text }, set: onChange), axis: .vertical)
            .textStyle(Typography.bodyLarge)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.outline, lineWidth: 1))
    }

    @ViewBuilder
    private func lines(_ fieldKind: ClipField, heading: String, add: String) -> some View {
        let lines = vm.uiState.draft.lines(fieldKind)
        HStack {
            Text(heading).textStyle(Typography.titleMedium)
            Spacer()
            Button(add) { vm.onLineAdd(fieldKind) }.buttonStyle(TextActionStyle())
        }
        .padding(.top, 8)
        ForEach(lines.indices, id: \.self) { index in
            HStack(spacing: 4) {
                field("", text: lines[index]) { vm.onLineChange(fieldKind, index, $0) }
                Button { vm.onLineRemove(fieldKind, index) } label: {
                    Image(systemName: "xmark").foregroundStyle(Palette.muted).frame(width: 40, height: 40)
                }
                .accessibilityLabel(Strings.clipRemoveLine(index + 1))
            }
        }
    }
}
