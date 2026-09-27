package com.example.recipeclipper.fake

import com.example.recipeclipper.data.local.UseUpLog

/** When each recipe's pantry use-up sheet was last settled (#147), in memory. */
class FakeUseUpLog(override var useUps: Map<Long, Long> = emptyMap()) : UseUpLog
