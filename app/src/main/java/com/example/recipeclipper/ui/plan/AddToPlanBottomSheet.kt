package com.example.recipeclipper.ui.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.ui.recipe.SectionHeading

/**
 * "Add to plan" (#49), from the recipe screen's overflow menu: a day of this week or next, a
 * meal type (Dinner to start with) and the servings (the recipe's yield to start with), then
 * one button. The [viewModel] comes from the recipe screen, which has told it the recipe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlanBottomSheet(viewModel: AddToPlanViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(state.added) {
        if (state.added) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        PlanSheetContent(
            title = stringResource(R.string.action_add_to_plan),
            days = state.days,
            today = state.today,
            selectedDay = state.selectedDay,
            onDaySelected = viewModel::onDaySelected,
            mealTypes = state.mealTypes,
            selectedMealTypeId = state.selectedMealTypeId,
            onMealTypeSelected = viewModel::onMealTypeSelected,
            servings = state.servings,
            onServingsChange = viewModel::onServingsChange,
            confirmLabel = stringResource(R.string.action_add_to_day, dayTitle(state.selectedDay)),
            confirmEnabled = state.canAdd,
            onConfirm = viewModel::onAdd
        )
    }
}

/** The body shared by "Add to plan" and the Week's "Move": days, meal type, maybe servings. */
@Composable
internal fun PlanSheetContent(
    title: String,
    days: List<Long>,
    today: Long,
    selectedDay: Long,
    onDaySelected: (Long) -> Unit,
    mealTypes: List<MealType>,
    selectedMealTypeId: Long?,
    onMealTypeSelected: (Long) -> Unit,
    servings: Int?,
    onServingsChange: (Int) -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
    ) {
        SectionHeading(title)
        Spacer(Modifier.height(12.dp))
        DayStrip(days = days, today = today, selected = selectedDay, onSelect = onDaySelected)
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.label_meal),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        MealTypeChoices(types = mealTypes, selected = selectedMealTypeId, onSelect = onMealTypeSelected)
        if (servings != null) {
            Spacer(Modifier.height(16.dp))
            ServingsPicker(servings = servings, onChange = onServingsChange)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}
