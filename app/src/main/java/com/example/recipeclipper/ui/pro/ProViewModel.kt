package com.example.recipeclipper.ui.pro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.ProAccess
import com.example.recipeclipper.data.ProState
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.needsNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Pro screen (#413): [pro] as the store and Developer settings have it, [busy] while the
 * store's sheet or a restore is under way (so the buttons can't be doubled), and [notice] a
 * purchase or restore that didn't simply unlock, until the next one starts.
 */
data class ProUiState(
    val pro: ProState = ProState(),
    val busy: Boolean = false,
    val notice: PurchaseOutcome? = null
)

/**
 * Settings' Pro page (#413), which opens only while Pro has an extra: what Pro adds,
 * the unlock (the one-time purchase of #107, now Pro) and Restore purchases. The price is the
 * store's, shown only once the store has given it.
 */
@HiltViewModel
class ProViewModel @Inject constructor(
    private val pro: ProAccess
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProUiState(pro = pro.current))
    val uiState: StateFlow<ProUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            pro.states.collect { state -> _uiState.update { it.copy(pro = state) } }
        }
    }

    /** "Unlock Pro": the store's purchase sheet. */
    fun onUnlock() = run { pro.purchase() }

    /** "Restore purchases": asks the store for this account's purchase again. */
    fun onRestore() = run { pro.restore() }

    private fun run(action: suspend () -> PurchaseOutcome) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val outcome = action()
            _uiState.update { it.copy(busy = false, notice = outcome.takeIf { o -> o.needsNotice }) }
        }
    }
}
