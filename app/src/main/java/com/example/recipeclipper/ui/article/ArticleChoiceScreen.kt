package com.example.recipeclipper.ui.article

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Several recipes written inside one article (#435): the page's photo and headline, then one
 * plain row per recipe with its name and counts. A row opens the editor on that recipe ([onCheck],
 * the page's link and the recipe's place); one saved already says so and opens as itself.
 */
@Composable
fun ArticleChoiceScreen(
    onBack: () -> Unit,
    onCheck: (url: String, index: Int) -> Unit,
    onOpenSaved: (recipeId: Long) -> Unit,
    viewModel: ArticleChoiceViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Back from the editor or a saved recipe: which are saved by now.
    LifecycleResumeEffect(viewModel) {
        viewModel.onShown()
        onPauseOrDispose { }
    }
    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp)
            ) {
                BackButton(onBack)
                Spacer(Modifier.height(16.dp))
                when {
                    state.loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    state.rows.isEmpty() -> Text(
                        stringResource(R.string.article_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Choices(state, onCheck, onOpenSaved)
                }
            }
        }
    }
}

@Composable
private fun Choices(state: ArticleChoiceUiState, onCheck: (String, Int) -> Unit, onOpenSaved: (Long) -> Unit) {
    state.image?.let { image ->
        AsyncImage(
            model = image,
            contentDescription = state.headline,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp))
        )
        Spacer(Modifier.height(16.dp))
    }
    Text(state.headline, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.article_choose_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.article_choose_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(Modifier.height(4.dp))
    state.rows.forEach { row ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { row.savedId?.let(onOpenSaved) ?: onCheck(state.pageUrl, row.index) }
                .padding(vertical = 14.dp)
        ) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(
                if (row.savedId != null) stringResource(R.string.article_choose_saved)
                else stringResource(R.string.article_choose_counts, row.ingredients, row.steps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
