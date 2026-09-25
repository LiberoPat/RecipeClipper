package com.example.recipeclipper.ui.week

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.Menu

/**
 * A name for a menu (#52): "Save week as menu" starts empty, "Rename" starts on the menu's name.
 * The text is held with rememberSaveable, so it survives rotation.
 */
@Composable
internal fun MenuNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.menu_name_hint)) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("menuName")
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Confirms deleting [menu]; the plan and the recipes are untouched. */
@Composable
internal fun DeleteMenuDialog(menu: Menu, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_menu_title, menu.name)) },
        text = { Text(stringResource(R.string.delete_menu_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** The menus sheet and dialogs of the Week tab (#52), each shown while its state says so. */
@Composable
internal fun WeekMenus(state: MenusUiState, viewModel: WeekViewModel) {
    if (state.saving) {
        MenuNameDialog(
            title = stringResource(R.string.save_menu_title),
            confirmLabel = stringResource(R.string.action_save),
            initial = "",
            onConfirm = viewModel::onSaveMenu,
            onDismiss = viewModel::onSaveMenuDismissed
        )
    }
    if (state.picking) {
        MenusSheet(
            menus = state.menus,
            onApply = viewModel::onApplyMenu,
            onRename = viewModel::onRenameMenuStart,
            onDelete = viewModel::onDeleteMenuStart,
            onDismiss = viewModel::onPickMenuDismissed
        )
    }
    state.renaming?.let { menu ->
        MenuNameDialog(
            title = stringResource(R.string.rename_menu_title),
            confirmLabel = stringResource(R.string.action_rename),
            initial = menu.name,
            onConfirm = viewModel::onRenameMenu,
            onDismiss = viewModel::onRenameMenuDismissed
        )
    }
    state.deleting?.let { menu ->
        DeleteMenuDialog(menu, onConfirm = viewModel::onDeleteMenuConfirm, onDismiss = viewModel::onDeleteMenuDismissed)
    }
}

/** The snackbar text for a menu action. */
internal fun menuMessageText(resources: android.content.res.Resources, message: MenuMessage): String = when (message) {
    is MenuMessage.Saved -> resources.getString(R.string.menu_saved, message.name)
    MenuMessage.SaveFailed -> resources.getString(R.string.menu_save_failed)
    is MenuMessage.Applied -> resources.getQuantityString(R.plurals.menu_applied, message.count, message.count, message.name)
}
