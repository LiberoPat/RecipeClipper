package com.example.recipeclipper.ui.sharefile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.BackupFiles
import com.example.recipeclipper.data.ShareFileRepository
import com.example.recipeclipper.data.backup.ShareFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A shared file written and ready for the share sheet: its URI and the name it goes by. */
data class SentFile(val uri: String, val name: String)

/**
 * "Send as file" (#149, phase 2). [file] is set once the file is written, for the screen to hand
 * to the share sheet; [failed] once it couldn't be. The screen clears both when it has acted.
 */
data class SendFileUiState(
    val busy: Boolean = false,
    val file: SentFile? = null,
    val failed: Boolean = false
)

/**
 * Writes the shared file for one recipe (the recipe screen's menu) or for the grocery list's
 * unticked items and their recipes (the Groceries menu). Nothing leaves the phone from here:
 * the screen opens the user's own share sheet on the file.
 */
@HiltViewModel
class SendFileViewModel @Inject constructor(
    private val share: ShareFileRepository,
    private val files: BackupFiles
) : ViewModel() {

    private val _uiState = MutableStateFlow(SendFileUiState())
    val uiState: StateFlow<SendFileUiState> = _uiState.asStateFlow()

    /** Recipe [recipeId], named for its [title]. */
    fun sendRecipe(recipeId: Long, title: String) = send(title) { share.recipeFile(recipeId) }

    /** The grocery list's unticked items, named [title] (the Groceries tab's name). */
    fun sendGroceries(title: String) = send(title) { share.groceriesFile() }

    /** The share sheet was opened on [SendFileUiState.file]. */
    fun onSent() {
        _uiState.value = SendFileUiState()
    }

    /** The failure was shown. */
    fun onFailureShown() {
        _uiState.value = SendFileUiState()
    }

    private fun send(title: String, make: suspend () -> String?) {
        if (_uiState.value.busy) return
        _uiState.value = SendFileUiState(busy = true)
        viewModelScope.launch {
            val name = ShareFile.fileName(title)
            val uri = make()?.let { files.writeShare(it, name) }
            _uiState.value = if (uri == null) SendFileUiState(failed = true) else SendFileUiState(file = SentFile(uri, name))
        }
    }
}
