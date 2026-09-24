package com.example.recipeclipper.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** [AppInfo] from `Build` and the installed package, the same arrangement as `AndroidConnectivity`. */
@Singleton
class AndroidAppInfo @Inject constructor(
    @ApplicationContext context: Context
) : AppInfo {

    override val platform: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    override val appVersion: String = try {
        @Suppress("DEPRECATION") // the flags overload is API 33+; minSdk is 24
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }
}
