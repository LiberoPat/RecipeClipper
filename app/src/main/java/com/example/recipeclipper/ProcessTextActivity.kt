package com.example.recipeclipper

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Recipe Clipper" in any app's text-selection menu (#306): a trampoline. The menu starts its
 * target for a result, inside the other app's task, so landing here and forwarding to the
 * single-task [MainActivity] in its own task is what keeps a second copy of the app from
 * opening inside the other app (#334). Nothing is shown; the app's own selection menus leave it
 * out ([OwnProcessTextFilter]).
 */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        if (!text.isNullOrBlank()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }
}

/**
 * Offers [ProcessTextActivity] in other apps' selection menus only while the shareText flag is
 * on (#334): the component is enabled and disabled with the flag, as the app sees it change in
 * Developer settings. Started once per process.
 */
class ProcessTextEntry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val featureFlags: FeatureFlags
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            featureFlags.values.map { it.isOn(Flag.SHARE_TEXT) }.distinctUntilChanged().collect(::offer)
        }
    }

    /** Whether the entry is offered now: the manifest's default (enabled) until the flag says otherwise. */
    fun isOffered(): Boolean =
        context.packageManager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun offer(on: Boolean) {
        if (isOffered() == on) return
        context.packageManager.setComponentEnabledSetting(
            component,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private val component get() = ComponentName(context, ProcessTextActivity::class.java)
}
