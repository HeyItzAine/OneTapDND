package com.example.onetapdnd

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class DailyWindowTest {
    private fun time(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.JANUARY, day, hour, minute)
    }.timeInMillis

    @Test fun startsAtChosenMinuteAndEndsAfterDuration() {
        val window = DailyWindow(14 * 60 + 55, 30)
        assertFalse(window.active(time(3, 14, 54)))
        assertTrue(window.active(time(3, 14, 55)))
        assertTrue(window.active(time(3, 15, 24)))
        assertFalse(window.active(time(3, 15, 25)))
        assertEquals(time(3, 15, 25), window.nextBoundary(time(3, 14, 55)))
        assertEquals(time(4, 14, 55), window.nextBoundary(time(3, 15, 25)))
    }

    @Test fun midnightWindowKeepsPreviousDaysStart() {
        val window = DailyWindow(23 * 60 + 50, 30)
        assertTrue(window.active(time(4, 0, 10)))
        assertFalse(window.active(time(4, 0, 20)))
        assertEquals(time(4, 0, 20), window.nextBoundary(time(4, 0, 0)))
    }

    @Test fun modeTriggerFlagsMatchSelector() {
        assertFalse(AutomationMode.TIME.usesLocation)
        assertFalse(AutomationMode.LOCATION.usesTime)
        assertTrue(AutomationMode.HYBRID.usesLocation)
        assertTrue(AutomationMode.HYBRID.usesTime)
    }
}
