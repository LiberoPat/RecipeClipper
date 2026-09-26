package com.example.recipeclipper.walkthrough

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * The walkthrough videos' runner (#106), chosen by `-Pwalkthrough` (app/build.gradle.kts):
 * Hilt's test Application, so [WalkthroughTest] can swap Chef mode's model for a stub. The other
 * device tests keep the real Application and the plain runner.
 */
class WalkthroughRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
