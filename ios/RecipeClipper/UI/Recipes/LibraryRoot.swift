import SwiftUI

/// What makes the library the Recipes tab's first screen (#375, `libraryFirst`): it takes over
/// Home's jobs. `vm` is `HomeViewModel` (it already follows the cook's place, today's meals, the
/// restore and the Ask an AI sheet; Home is gone, so nothing else uses it) and the closures are the
/// card's and the gear's destinations.
struct LibraryRoot {
    let vm: HomeViewModel
    let onOpenSettings: () -> Void
    /// "Resume cooking": the recipe, in cook mode.
    let onResumeCooking: (Int64) -> Void
    /// Today's meal: the planned recipe, its planned servings, and whether to start cooking it.
    let onOpenPlanned: (Int64, Int?, Bool) -> Void
    /// Today's card (#402): off, only a recipe being cooked gets a card.
    var homeTonight = true
    /// "Ask an AI" (#351): nil (the askAi flag off) leaves it out of the sheet.
    var makeAskAiVM: (() -> AskAiViewModel)?
    /// The Pro screen (#413): Ask an AI's locked Plan my week choices open it.
    var onOpenPro: () -> Void = {}
}

/// The gear and the + beside the title (#375): Settings, and the Add a recipe sheet.
struct LibraryTopActions: View {
    let onOpenSettings: () -> Void
    let onAdd: () -> Void
    /// The gear and the + grow a little with the title beside them, capped: they are chrome.
    @ScaledMetric(relativeTo: .title) private var scaled: CGFloat = 20
    private var size: CGFloat { min(scaled, 30) }

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onOpenSettings) {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: size))
                    .foregroundStyle(Palette.muted)
                    .frame(width: max(44, size + 16), height: max(44, size + 16))
                    .contentShape(Rectangle())
            }
            // Plain: in a list row, buttons with the default style all fire on one tap.
            .buttonStyle(.plain)
            .accessibilityLabel(Strings.navSettings)
            .accessibilityIdentifier("library.settings")
            // The paprika +: the tour's tooltip points at it.
            Button(action: onAdd) {
                Image(systemName: "plus")
                    .font(.system(size: size * 0.8, weight: .semibold))
                    .foregroundStyle(Palette.onPrimary)
                    .frame(width: size + 16, height: size + 16)
                    .background(Palette.primary, in: Circle())
                    .frame(width: max(44, size + 20), height: max(44, size + 20))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Strings.addRecipe)
            .accessibilityIdentifier("library.add")
            .tooltipAnchor(.homeLink, side: .below)
        }
        .offset(x: 6) // optical edge, past the buttons' own padding
    }
}

/// The compact card above search (#375, from #405's Today and Continue cooking): a thumbnail, what
/// it is ("Today · Lunch", or "Continue cooking"), the name, one line (the next meal, or the step
/// and the countdown) and the one button. Only a recipe being cooked, or today's next meal while
/// `homeTonight` is on, gets one; with neither there is nothing here, never an "Open recipe" card.
/// It is the library's first item, so it scrolls away with the list.
struct LibraryCard: View {
    let state: HomeUiState
    let root: LibraryRoot
    let onOpenRecipe: (Int64) -> Void

    var body: some View {
        if let latest = state.continueCooking, let resume = state.resume {
            CompactCard(
                imageUrl: latest.imageUrl, noPhoto: latest.noPhoto, label: Strings.sectionContinueCooking, title: latest.title,
                button: Strings.homeResumeCooking, onButton: { root.onResumeCooking(latest.id) },
                onOpen: { onOpenRecipe(latest.id) }
            ) {
                ResumeLine(resume: resume)
            }
        } else if root.homeTonight, let today = state.today {
            let lead = today.lead
            CompactCard(
                imageUrl: lead.imageUrl,
                noPhoto: lead.recipeId == nil ? nil : lead.noPhoto,
                label: lead.mealType.isEmpty ? Strings.homeToday : "\(Strings.homeToday) · \(lead.mealType)",
                title: lead.title,
                button: lead.recipeId == nil ? nil : Strings.startCooking,
                onButton: { if let id = lead.recipeId { root.onOpenPlanned(id, lead.servings, true) } },
                onOpen: lead.recipeId.map { id in { root.onOpenPlanned(id, lead.servings, false) } }
            ) {
                if let next = today.others.first {
                    Button {
                        if let id = next.recipeId { root.onOpenPlanned(id, next.servings, false) }
                    } label: {
                        Text(Strings.homeTodayOther(next.mealType, next.title))
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(Palette.muted)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(next.recipeId == nil)
                    .accessibilityIdentifier("library.card.other")
                }
            }
        }
    }
}

private struct CompactCard<Line: View>: View {
    let imageUrl: String?
    /// What the designed image (#403) is made from; nil (a planned note) is the plain block.
    let noPhoto: NoPhotoSource?
    let label: String
    let title: String
    let button: String?
    let onButton: () -> Void
    let onOpen: (() -> Void)?
    @ViewBuilder let line: () -> Line
    @Environment(\.noPhotoImage) private var noPhotoImage

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Button { onOpen?() } label: {
                    HStack(spacing: 12) {
                        thumbnail
                        VStack(alignment: .leading, spacing: 2) {
                            SmallCapsLabel(label)
                            Text(title)
                                .textStyle(Typography.titleMedium)
                                .foregroundStyle(Palette.onBackground)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(onOpen == nil)
                .accessibilityIdentifier("library.card.open")
            }
            line()
            if let button {
                Button(button, action: onButton)
                    .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                    .accessibilityIdentifier("library.card.start")
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surfaceContainer)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("library.card")
    }

    /// The photo; with none, the recipe's designed image (#403) or a plain themed block: never a stock image.
    @ViewBuilder private var thumbnail: some View {
        if noPhotoImage, let noPhoto {
            RecipeImage(url: imageUrl, recipe: noPhoto)
                .frame(width: 64, height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .accessibilityHidden(true)
        } else if let image = imageUrl, let url = URL(string: image) {
            Color.clear
                .frame(width: 64, height: 64)
                .overlay { CachedAsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { Palette.hairline } }
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .accessibilityHidden(true)
        } else {
            RoundedRectangle(cornerRadius: 10).fill(Palette.accentText.opacity(0.12))
                .frame(width: 64, height: 64)
                .accessibilityHidden(true)
        }
    }
}

/// Where the cook stopped: the running timer's time left, then "Step 2 of 4".
private struct ResumeLine: View {
    let resume: CookResume

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { content }
            VStack(alignment: .leading, spacing: 4) { content }
        }
    }

    @ViewBuilder private var content: some View {
        if let seconds = resume.timerSecondsLeft {
            Label(Strings.homeTimerLeft(StepTimers.clock(seconds)), systemImage: "timer")
                .textStyle(Typography.labelMedium)
                .foregroundStyle(Palette.accentText)
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .background(Palette.accentText.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
        }
        Text(Strings.cookPosition(resume.step, of: resume.stepCount))
            .textStyle(Typography.bodySmall)
            .foregroundStyle(Palette.muted)
    }
}

/// "Add a recipe" (#375): the + 's sheet, replacing #102's menu. Paste a link, Scan a recipe (nil
/// `onScan`: the photoText flag is off), Type a recipe and Ask an AI (nil `onAskAi`: askAi is off),
/// each with its line, then the line about sharing from any app. A choice goes where it leads once
/// the sheet is away (`onChoose`), so the next sheet or screen never opens over a closing one.
struct AddRecipeSheet: View {
    let onPasteLink: () -> Void
    let onScan: (([Data]) -> Void)?
    let onTypeRecipe: () -> Void
    let onAskAi: (() -> Void)?
    /// Closes the sheet and runs the action after it has gone.
    let onChoose: (@escaping () -> Void) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(Strings.addRecipe)
                .textStyle(Typography.titleLarge)
                .foregroundStyle(Palette.onBackground)
                .accessibilityAddTraits(.isHeader)
                .padding(.bottom, 6)
            Button { onChoose(onPasteLink) } label: {
                row(icon: "link", name: Strings.pasteLink, hint: Strings.homePasteLinkHint)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("add.pasteLink")
            if let onScan {
                ScanRecipeButton(onScan: { pages in onChoose { onScan(pages) } }) {
                    row(icon: "camera", name: Strings.scanRecipe, hint: Strings.homeScanHint)
                }
            }
            Button { onChoose(onTypeRecipe) } label: {
                row(icon: "pencil", name: Strings.typeRecipe, hint: Strings.homeTypeHint)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("add.typeRecipe")
            if let onAskAi {
                Button { onChoose(onAskAi) } label: {
                    row(icon: "sparkles", name: Strings.askAi, hint: Strings.homeAskAiHint)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("add.askAi")
            }
            Text(Strings.homeShareHint)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.muted)
                .padding(.top, 12)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.background)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("add.sheet")
    }

    private func row(icon: String, name: String, hint: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Palette.accentText)
                .frame(width: 40, height: 40)
                .background(Palette.accentText.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).textStyle(Typography.titleSmall).foregroundStyle(Palette.onBackground)
                Text(hint).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text("›").textStyle(Typography.titleLarge).foregroundStyle(Palette.muted).accessibilityHidden(true)
        }
        .frame(minHeight: 56)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// The empty library (#375), in the list's place: the four ways in as tiles, the line about
/// sharing, and "Restore from a backup file" (#150) with how the restore went. The sample recipe
/// (#151), when that is all there is, is the one row under it.
struct EmptyLibraryView: View {
    let onPasteLink: () -> Void
    let onScan: (([Data]) -> Void)?
    let onTypeRecipe: () -> Void
    let onAskAi: (() -> Void)?
    let restore: BackupStatus
    /// Nil: no restore can be offered.
    let onRestore: (() -> Void)?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(Strings.libraryEmptyTitle)
                .textStyle(Typography.titleLarge)
                .foregroundStyle(Palette.onBackground)
                .accessibilityAddTraits(.isHeader)
            let columns = dynamicTypeSize.isAccessibilitySize
                ? [GridItem(.flexible())]
                : [GridItem(.flexible(), spacing: 8, alignment: .top), GridItem(.flexible(), spacing: 8, alignment: .top)]
            LazyVGrid(columns: columns, spacing: 8) {
                Button(action: onPasteLink) {
                    tile(icon: "link", name: Strings.pasteLink, hint: Strings.homePasteLinkHint)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("library.empty.pasteLink")
                if let onScan {
                    ScanRecipeButton(onScan: onScan) {
                        tile(icon: "camera", name: Strings.scanRecipe, hint: Strings.homeScanHint)
                    }
                }
                Button(action: onTypeRecipe) {
                    tile(icon: "pencil", name: Strings.typeRecipe, hint: Strings.homeTypeHint)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("library.empty.typeRecipe")
                if let onAskAi {
                    Button(action: onAskAi) {
                        tile(icon: "sparkles", name: Strings.askAi, hint: Strings.homeAskAiHint)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("library.empty.askAi")
                }
            }
            Text(Strings.homeShareHint)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.muted)
            if let onRestore {
                Button(Strings.homeRestoreBackup, action: onRestore)
                    .buttonStyle(TextActionStyle())
                    .disabled(restore == .importing)
                    .accessibilityIdentifier("library.restore")
            }
            if let status = backupStatusText(restore) {
                Text(status.text)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(status.isError ? Palette.error : Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("library.restoreStatus")
            }
        }
        .padding(.top, 10)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("library.empty")
    }

    private func tile(icon: String, name: String, hint: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Palette.accentText)
                .accessibilityHidden(true)
            Text(name).textStyle(Typography.titleSmall).foregroundStyle(Palette.onBackground)
            Text(hint).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 14))
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }
}
