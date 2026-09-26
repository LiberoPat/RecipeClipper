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
        VStack(spacing: 0) {
            ZStack {
                ClipWebPage(
                    url: state.url,
                    fixtureHTML: fixtureHTML,
                    syncState: Self.syncJson(state.draft, newMarkId: state.newMarkId),
                    pickingPhoto: state.pickingPhoto,
                    onEvent: { event in
                        switch event {
                        case .selection(let text): vm.onSelectionChanged(text)
                        case .tagTapped(let field): vm.onTagTapped(field)
                        case .imageTapped(let src): vm.onImageTapped(src)
                        }
                    }
                )
                if state.reviewing {
                    ClipReviewPane(vm: vm)
                }
            }
            if !state.reviewing {
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
                Text(SourceDomain.of(state.url) ?? "")
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(1)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(Strings.done, action: vm.onReview)
                    .disabled(!state.draft.canFinish || state.reviewing)
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
        .libraryFullAlert(
            isPresented: Binding(get: { vm.uiState.libraryFull }, set: { if !$0 { vm.onLibraryFullDismiss() } }),
            onUnlock: vm.onUnlock
        )
    }

    @ViewBuilder
    private func noticeView(_ notice: ClipNotice) -> some View {
        let text = Strings.clipMessage(notice.message)
        switch notice.message {
        case .assigned, .cleared:
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
        }
    }

    /// The argument to `RC.sync`: the marks this draft holds, with their tags' labels.
    static func syncJson(_ draft: ClipDraft, newMarkId: String?) -> String {
        var marks: [[String: String]] = []
        for field in ClipField.allCases where field != .photo {
            guard let id = draft.marks[field] else { continue }
            let count = draft.count(field)
            let label = field == .name || count == 0
                ? Strings.clipField(field) : Strings.clipTagCount(Strings.clipField(field), count)
            marks.append(["id": id, "field": field.rawValue, "label": label])
        }
        var json: [String: Any] = ["marks": marks]
        if let newMarkId { json["newId"] = newMarkId }
        if let photo = draft.photo { json["photo"] = ["src": photo, "label": Strings.clipField(.photo)] }
        guard let data = try? JSONSerialization.data(withJSONObject: json),
              let string = String(data: data, encoding: .utf8)
        else { return "{}" }
        return string
    }
}

/// The toolbar under the page: what is selected (or the summary), and one button per field.
private struct ClipToolbar: View {
    let vm: ClipViewModel

    var body: some View {
        let state = vm.uiState
        let draft = state.draft
        let selecting = !state.selection.isEmpty
        VStack(alignment: .leading, spacing: 6) {
            Hairline()
            Group {
                if selecting {
                    Text(Strings.clipLinesSelected(state.selection.count))
                        .textStyle(Typography.labelLarge)
                        .foregroundStyle(Palette.muted)
                    ForEach(Array(state.selection.prefix(2).enumerated()), id: \.offset) { _, line in
                        Text(line).textStyle(Typography.bodyMedium).lineLimit(1)
                    }
                } else if state.pickingPhoto {
                    Text(Strings.clipPickingPhoto)
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.accentText)
                } else {
                    HStack {
                        Text(Strings.clipSummary(draft))
                            .textStyle(Typography.labelLarge)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if draft.canFinish {
                            Button(Strings.clipReview, action: vm.onReview)
                                .buttonStyle(TextActionStyle())
                        }
                    }
                    Text(Strings.clipHint)
                        .textStyle(Typography.bodySmall)
                        .foregroundStyle(Palette.muted)
                }
            }
            .padding(.horizontal, 16)
            // With a selection, a count shows what the field would hold: assigning replaces.
            HStack(spacing: 6) {
                fieldButton(.name, Strings.clipField(.name), enabled: selecting) { vm.onAssign(.name) }
                fieldButton(
                    .ingredients,
                    Strings.clipField(.ingredients) + (selecting ? " \(state.selection.count)" : ""),
                    enabled: selecting
                ) { vm.onAssign(.ingredients) }
                fieldButton(
                    .steps,
                    Strings.clipField(.steps) + (selecting ? " \(state.selection.count)" : ""),
                    enabled: selecting
                ) { vm.onAssign(.steps) }
                fieldButton(.photo, Strings.clipField(.photo), enabled: true, selected: state.pickingPhoto, action: vm.onPhotoButton)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .background(Palette.background)
    }

    private func fieldButton(
        _ field: ClipField, _ label: String, enabled: Bool, selected: Bool = false, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(label)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(ClipFieldButtonStyle(selected: selected))
        .disabled(!enabled)
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
                    .disabled(!draft.canFinish || state.saving)
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
