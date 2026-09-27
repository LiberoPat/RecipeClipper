package com.example.recipeclipper.ui.recipe

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import kotlinx.coroutines.launch

/** What the reading view shows of "Your cooks" (#116): how many photos, and the section itself. */
internal class CookedPhotosUi(val count: Int, val section: @Composable () -> Unit)

/**
 * The recipe screen's side of "I made this" (#116): the section, the full-screen photo, and the
 * snackbars (a picture that couldn't be added, no camera app, Undo for a deleted photo). Null
 * while the flag is off (no ViewModel) or before the recipe has loaded. [onMadeThis] runs once
 * a photo just added, or a "Mark as cooked" entry (#173), has been closed: the recipe was cooked (#147).
 */
@Composable
internal fun cookedPhotosUi(
    viewModel: CookedPhotosViewModel?,
    content: RecipeContent,
    snackbarHostState: SnackbarHostState,
    onMadeThis: () -> Unit = {}
): CookedPhotosUi? {
    if (viewModel == null || content !is RecipeContent.Success) return null
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noCamera = stringResource(R.string.camera_unavailable)
    val sources = rememberPhotoSources(
        onPicked = viewModel::onAdd,
        onNoCamera = { scope.launch { snackbarHostState.showSnackbar(noCamera) } }
    )

    val addFailed = stringResource(R.string.cooked_add_failed)
    LaunchedEffect(state.addFailed) {
        if (state.addFailed) {
            viewModel.onAddFailedShown()
            snackbarHostState.showSnackbar(addFailed)
        }
    }
    LaunchedEffect(state.madeThis) {
        if (state.madeThis) {
            onMadeThis()
            viewModel.onMadeThisHandled()
        }
    }
    val photoDeleted = stringResource(R.string.cooked_photo_deleted)
    val markDeleted = stringResource(R.string.cooked_mark_deleted)
    val undo = stringResource(R.string.action_undo)
    val deleted = state.deleted
    LaunchedEffect(deleted?.id) {
        if (deleted != null) {
            val message = if (deleted.hasPhoto) photoDeleted else markDeleted
            val result = snackbarHostState.showSnackbar(message, undo, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.onUndoDelete() else viewModel.onDeleteSettled()
        }
    }

    val shareTitle = stringResource(R.string.cd_share_photo)
    val actions = remember(viewModel, context, content.recipe.name) {
        CookedPhotoActions(
            onClose = viewModel::onClose,
            onNoteChange = viewModel::onNoteChange,
            onDayChange = viewModel::onDayChange,
            onDelete = viewModel::onDelete,
            onShare = { photo -> photo.path?.let { sharePhoto(context, it, content.recipe.name, shareTitle) } }
        )
    }
    state.open?.let { CookedPhotoViewer(it, state.noteDraft, actions) }

    // The recipe's delete confirmation counts photos; a cooking marked without one (#173) isn't a photo.
    return CookedPhotosUi(state.photos.count { it.hasPhoto }) {
        CookedPhotosSection(state.photos, sources, viewModel::onOpen, viewModel::onMarkCooked)
    }
}
