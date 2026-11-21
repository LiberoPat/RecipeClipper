package com.example.recipeclipper.data.flags

/**
 * Library first (#375): the Recipes tab opens the library. It needs `oneCookbook` (#345), whose views strip
 * carries the Lists card that Home's Lists row was the only way to without it, so with that off the tab
 * keeps opening Home.
 */
val FlagValues.libraryFirst: Boolean get() = isOn(Flag.LIBRARY_FIRST) && isOn(Flag.ONE_COOKBOOK)
