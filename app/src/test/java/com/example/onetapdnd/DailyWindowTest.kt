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

    @Test fun multipleQuietTimesLeaveGapsBetweenWindows() {
        val schedule = DailySchedule(listOf(
            DailyQuietTime("morning", DailyWindow(8 * 60, 30)),
            DailyQuietTime("evening", DailyWindow(18 * 60, 60))
        ))
        assertTrue(schedule.active(time(3, 8, 15)))
        assertFalse(schedule.active(time(3, 12, 0)))
        assertTrue(schedule.active(time(3, 18, 30)))
        assertEquals(time(3, 18, 0), schedule.nextBoundary(time(3, 12, 0)))
    }

    @Test fun overlapRemainsActiveUntilLastWindowEnds() {
        val schedule = DailySchedule(listOf(
            DailyQuietTime("first", DailyWindow(8 * 60, 60)),
            DailyQuietTime("second", DailyWindow(8 * 60 + 30, 60))
        ))
        assertTrue(schedule.active(time(3, 9, 0)))
        assertTrue(schedule.active(time(3, 9, 29)))
        assertFalse(schedule.active(time(3, 9, 30)))
    }

    @Test fun disabledAndDeletedTimesNeverRequestDndOrAnAlarm() {
        val disabled = DailySchedule(listOf(DailyQuietTime("disabled", DailyWindow(8 * 60, 30), enabled = false)))
        assertFalse(disabled.active(time(3, 8, 15)))
        assertNull(disabled.nextBoundary(time(3, 8, 15)))
        assertFalse(DailySchedule(emptyList()).active(time(3, 8, 15)))
        assertNull(DailySchedule(emptyList()).nextBoundary(time(3, 8, 15)))
    }

    @Test fun midnightAndMorningWindowsRepeatIndependently() {
        val schedule = DailySchedule(listOf(
            DailyQuietTime("night", DailyWindow(23 * 60 + 50, 30)),
            DailyQuietTime("morning", DailyWindow(8 * 60, 30))
        ))
        assertTrue(schedule.active(time(4, 0, 10)))
        assertFalse(schedule.active(time(4, 0, 20)))
        assertEquals(time(4, 8, 0), schedule.nextBoundary(time(4, 0, 20)))
        assertTrue(schedule.active(time(4, 8, 15)))
    }

    @Test fun existingSingleWindowMigratesAndFreshInstallStartsEmpty() {
        val migrated = legacyQuietTimes(true, 14 * 60 + 55, 30)
        assertEquals(DailyWindow(14 * 60 + 55, 30), migrated.single().window)
        assertTrue(migrated.single().enabled)
        assertEquals("legacy", migrated.single().id)
        assertTrue(legacyQuietTimes(false, 14 * 60 + 55, 30).isEmpty())
    }
}
