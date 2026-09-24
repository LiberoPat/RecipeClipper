package com.example.recipeclipper.ui.recipe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.recipeclipper.timers.VisibleRecipe

// View-layer halves of the background timer alert. The ViewModel only schedules alarms through
// TimerAlarmScheduler; asking for permission and knowing what is on screen need an Activity.

/**
 * Returns a callback for "a timer is starting". On API 33+ it asks for POST_NOTIFICATIONS the
 * first time only (the app's one runtime prompt), remembered in its own preferences file so a
 * refusal isn't asked again on every timer. Refused or not, nothing else changes: the timer
 * runs and the in-app beep still sounds while the recipe is open.
 */
@Composable
internal fun rememberNotificationPrompt(): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return remember { {} }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context, launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            val prefs = context.getSharedPreferences(PROMPT_PREFS, Context.MODE_PRIVATE)
            if (!granted && !prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) {
                prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

private const val PROMPT_PREFS = "permission_prompts"
private const val KEY_NOTIFICATIONS_ASKED = "notificationsAsked"

/**
 * Marks [recipeId] as the recipe on screen while this screen is started, so a timer alarm for
 * it doesn't also post a notification over the in-app beep.
 */
@Composable
internal fun MarkRecipeVisible(recipeId: Long?) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, recipeId) {
        if (recipeId == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> VisibleRecipe.id = recipeId
                Lifecycle.Event.ON_STOP -> VisibleRecipe.clear(recipeId)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            VisibleRecipe.clear(recipeId)
        }
    }
}
