package com.example.recipeclipper.data

/**
 * The platform and app version, as a site report states them. An interface so the recipe
 * ViewModel never reads `Build` or `PackageManager` (a `Context` thing) and tests can pin the
 * values: [AndroidAppInfo] is the real implementation, bound in `di/PlatformModule`.
 */
interface AppInfo {
    /** e.g. "Android 14 (API 34)". */
    val platform: String

    /** e.g. "1.0 (1)": version name, then version code. */
    val appVersion: String
}
