package com.example.recipeclipper.fake

import com.example.recipeclipper.data.AppInfo

/** Fixed platform and version strings, so a report link can be asserted exactly. */
class FakeAppInfo(
    override val platform: String = "Android 14 (API 34)",
    override val appVersion: String = "1.0 (1)"
) : AppInfo
