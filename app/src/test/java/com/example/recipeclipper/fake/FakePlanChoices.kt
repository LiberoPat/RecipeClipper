package com.example.recipeclipper.fake

import com.example.recipeclipper.data.local.PlanChoices

/** What "Plan my week" remembers (#392), in memory. */
class FakePlanChoices(override var planMealKeys: List<String>? = null) : PlanChoices
