package com.example.recipeclipper.ui.pro

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.common.ProBadge
import com.example.recipeclipper.ui.common.noticeMessage
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * The Pro screen (#413): Settings' Pro page, listed only while Pro has an extra to add (none since
 * 2026-11-23: the Week extras are free, and Pro is kept for future AI features). What Pro
 * adds ([ProState.available]), "one-time purchase, never a subscription", the unlock (with the store's price once the
 * store gives it, never a figure of ours), Restore purchases, and "Free. No ads. No account. No
 * subscription." Unlocked, the button gives way to a thank-you line. iOS's `ProScreen` is the same page.
 */
@Composable
fun ProScreen(onBack: () -> Unit, viewModel: ProViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp)
                    .testTag("proScreen")
            ) {
                BackButton(onBack)
                Spacer(Modifier.height(12.dp))
                ProBadge()
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pro_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    stringResource(R.string.pro_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(16.dp))
                Hairline()
                state.pro.available.forEach { Feature(it.title, it.body) }
                Spacer(Modifier.height(20.dp))
                Hairline()
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.pro_one_time),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(12.dp))
                val pro = state.pro
                if (pro.isPro) {
                    Text(
                        stringResource(R.string.pro_unlocked),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("proUnlocked")
                    )
                } else {
                    Button(
                        onClick = viewModel::onUnlock,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("proUnlock")
                    ) {
                        Text(pro.price?.let { stringResource(R.string.pro_unlock_price, it) } ?: stringResource(R.string.pro_unlock))
                    }
                    TextButton(
                        onClick = viewModel::onRestore,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("proRestore")
                    ) {
                        Text(stringResource(R.string.pro_restore))
                    }
                    val status = when {
                        pro.pending -> R.string.unlimited_pending
                        else -> state.notice?.noticeMessage()
                    }
                    status?.let {
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("proNotice")
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.pro_free_line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("proFreeLine")
                )
            }
        }
    }
}

@Composable
private fun Feature(@StringRes title: Int, @StringRes body: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
