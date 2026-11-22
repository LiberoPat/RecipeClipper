package com.example.recipeclipper.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayCardChoiceTest {

    private val today = 20_000L

    @Test
    fun `shows when never closed and the setting is on`() {
        assertTrue(TodayCardChoice.shows(closable = true, showSetting = true, dismissedDay = null, today = today))
    }

    @Test
    fun `closed today hides it`() {
        assertFalse(TodayCardChoice.shows(closable = true, showSetting = true, dismissedDay = today, today = today))
    }

    @Test
    fun `closed yesterday shows it again`() {
        assertTrue(TodayCardChoice.shows(closable = true, showSetting = true, dismissedDay = today - 1, today = today))
    }

    @Test
    fun `a close dated after today (a clock moved back) does not hide it`() {
        assertTrue(TodayCardChoice.shows(closable = true, showSetting = true, dismissedDay = today + 1, today = today))
    }

    @Test
    fun `the setting off hides it whatever the close`() {
        assertFalse(TodayCardChoice.shows(closable = true, showSetting = false, dismissedDay = null, today = today))
        assertFalse(TodayCardChoice.shows(closable = true, showSetting = false, dismissedDay = today - 1, today = today))
    }

    @Test
    fun `with the flag off neither choice counts`() {
        assertTrue(TodayCardChoice.shows(closable = false, showSetting = false, dismissedDay = today, today = today))
        assertTrue(TodayCardChoice.shows(closable = false, showSetting = true, dismissedDay = null, today = today))
    }
}
