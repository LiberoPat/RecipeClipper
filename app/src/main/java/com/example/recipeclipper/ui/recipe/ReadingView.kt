package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.ui.tour.tooltipAnchor

/**
 * The reading view opens on the recipe: photo, title, times, ingredients. Servings and units
 * sit in one always-visible row just above the ingredients, and cooking is one bottom button.
 */
@Composable
internal fun ReadingView(
    content: RecipeContent.Success,
    state: RecipeUiState,
    actions: RecipeActions,
    isSaved: Boolean,
    photoCount: Int = 0,
    cookedPhotos: (@Composable () -> Unit)? = null
) {
    val recipe = content.recipe
    // Only whether the keyboard is up for the note, so the cooking bar steps aside for it.
    // Focus doesn't survive rotation anyway, so plain remember is right here.
    var editingNotes by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                ReadingTopBar(recipe, state, actions, isSaved, photoCount)
            }

            recipe.image?.let { image ->
                item {
                    AsyncImage(
                        model = image,
                        contentDescription = recipe.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            item {
                Text(recipe.name, style = MaterialTheme.typography.headlineSmall)
                val domain = content.sourceDomain
                if (domain != null) {
                    SourceCredit(
                        domain,
                        clipped = recipe.origin == ContentOrigin.CLIPPED,
                        onOpen = { actions.onOpenOriginal(recipe.sourceUrl) }
                    )
                    // Picked by the on-device model (#103): every line is on the page, but
                    // which lines were picked is the model's call, so say so, quietly.
                    if (recipe.origin == ContentOrigin.EXTRACTED) {
                        Text(
                            stringResource(R.string.extracted_from_page),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                } else {
                    Spacer(Modifier.height(14.dp))
                }
                Times(recipe.prepTime, recipe.cookTime, recipe.totalTime)
                Spacer(Modifier.height(16.dp))
                ServesUnitsRow(
                    servings = content.servings,
                    yieldText = recipe.yield,
                    words = content.words,
                    unitSystem = state.unitSystem,
                    onServingsChange = actions.onServingsChange,
                    onUnitSystemChange = actions.onUnitSystemChange
                )
                Spacer(Modifier.height(20.dp))
                SectionHeading(stringResource(R.string.heading_ingredients))
                Spacer(Modifier.height(6.dp))
            }

            itemsIndexed(content.ingredients) { index, ingredient ->
                if (content.isHeading(index)) {
                    IngredientHeadingRow(ingredient)
                } else {
                    IngredientRow(
                        text = ingredient,
                        checked = index in state.checkedIngredients,
                        onCheckedChange = { actions.onIngredientChecked(index, it) }
                    )
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                SectionHeading(stringResource(R.string.heading_instructions))
                Spacer(Modifier.height(6.dp))
            }

            itemsIndexed(content.instructions) { index, _ ->
                // Chef mode (#100): a step with a short version shows it; a tap shows it as written.
                val step = content.shownStep(index, state.asWrittenSteps)
                val toggle = if (content.hasShortStep(index)) {
                    val label = stringResource(
                        if (index in state.asWrittenSteps) R.string.step_show_short else R.string.step_show_as_written
                    )
                    Modifier.clickable(onClickLabel = label) { actions.onStepAsWrittenToggle(index) }
                } else {
                    Modifier
                }
                Row(Modifier.fillMaxWidth().then(toggle).padding(vertical = 8.dp)) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.width(32.dp)
                    )
                    Text(
                        stepText(step, content.shownStepAmounts(index, state.asWrittenSteps), MaterialTheme.colorScheme.tertiary),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // After the steps: the reading view still opens on the recipe, and a note like
            // "needs 10 more minutes" is read once the method is.
            // A recipe that wasn't kept (#107) has nowhere to keep a note.
            if (!state.notKept) item {
                Spacer(Modifier.height(24.dp))
                NotesSection(
                    notes = state.notes,
                    onNotesChange = actions.onNotesChange,
                    onFocusChange = { editingNotes = it }
                )
            }
            // "Your cooks" (#116): last, so the reading view still opens on the recipe.
            if (!state.notKept && cookedPhotos != null) item {
                Spacer(Modifier.height(28.dp))
                cookedPhotos()
            }
        }

        if (content.instructions.isNotEmpty() && !editingNotes) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Hairline()
                Button(
                    onClick = actions.onCookStart,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(52.dp)
                        .tooltipAnchor(Tooltip.RECIPE_START_COOKING)
                ) {
                    Text(stringResource(R.string.action_start_cooking), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
