package com.example.recipeclipper.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * The Week, Groceries and Pantry tabs until their features land (#49–#51): the tab's name, one
 * line on what it will hold, and "Coming soon". Deliberately quiet, with nothing to tap.
 */
@Composable
fun ComingSoonScreen(@StringRes title: Int, @StringRes description: Int) {
    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Hairline()
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.coming_soon),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
