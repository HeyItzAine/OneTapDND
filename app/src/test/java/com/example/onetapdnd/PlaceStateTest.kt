package com.example.onetapdnd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceStateTest {
    private val first = PlaceRule("first", "First", 0.0, 0.0, 200f)
    private val second = PlaceRule(
        "second",
        "Second",
        1.0,
        1.0,
        300f,
        PlaceAudioMode.DND_AND_SILENT
    )
    private val third = PlaceRule(
        "third",
        "Third",
        2.0,
        2.0,
        400f,
        PlaceAudioMode.DND_SILENT_MEDIA_ZERO
    )

    @Test
    fun rejectsInvalidCoordinatesAndRadii() {
        assertTrue(first.isValid())
        assertFalse(first.copy(latitude = Double.NaN).isValid())
        assertFalse(first.copy(longitude = Double.POSITIVE_INFINITY).isValid())
        assertFalse(first.copy(latitude = 90.1).isValid())
        assertFalse(first.copy(longitude = -180.1).isValid())
        assertFalse(first.copy(radiusMeters = 99f).isValid())
        assertFalse(first.copy(radiusMeters = 10001f).isValid())
        assertFalse(first.copy(name = " ").isValid())
        assertTrue(first.copy(latitude = -90.0, longitude = 180.0, radiusMeters = 100f).isValid())
    }

    @Test
    fun leavingOneOverlappingPlaceKeepsTheOtherActive() {
        val state = PlaceState(emptySet()).transition(setOf("first", "second"), true)
            .transition(setOf("first"), false)
        assertEquals(listOf(second), state.active(listOf(first, second)))
    }

    @Test
    fun strongestOverlappingModeWinsAndDowngradesOnExit() {
        val allInside = PlaceState(setOf("first", "second", "third"))
        assertEquals(
            PlaceAudioMode.DND_SILENT_MEDIA_ZERO,
            allInside.strongestMode(listOf(first, second, third))
        )
        val strongestExited = allInside.transition(setOf("third"), false)
        assertEquals(
            PlaceAudioMode.DND_AND_SILENT,
            strongestExited.strongestMode(listOf(first, second, third))
        )
    }

    @Test
    fun disabledDeletedAndUnknownPlacesCannotActivate() {
        val state = PlaceState(setOf("first", "deleted"))
        assertNull(state.strongestMode(listOf(first.copy(enabled = false))))
    }

    @Test
    fun duplicateAndUnknownExitIsHarmless() {
        val initial = PlaceState(setOf("first"))
        assertEquals(initial, initial.transition(setOf("unknown"), false))
        assertEquals(
            PlaceState(emptySet()),
            initial.transition(setOf("first"), false).transition(setOf("first"), false)
        )
    }

    @Test
    fun legacySilenceValuesMapToNewModes() {
        assertEquals(PlaceAudioMode.DND_ONLY, PlaceAudioMode.fromStored(null, false))
        assertEquals(PlaceAudioMode.DND_AND_SILENT, PlaceAudioMode.fromStored(null, true))
        assertEquals(
            PlaceAudioMode.DND_SILENT_MEDIA_ZERO,
            PlaceAudioMode.fromStored("DND_SILENT_MEDIA_ZERO", false)
        )
        assertEquals(PlaceAudioMode.DND_AND_SILENT, PlaceAudioMode.fromStored("invalid", true))
        assertTrue(PlaceAudioMode.requiresStoredMigration(null, false))
        assertTrue(PlaceAudioMode.requiresStoredMigration("invalid", false))
        assertTrue(PlaceAudioMode.requiresStoredMigration("DND_ONLY", true))
        assertFalse(PlaceAudioMode.requiresStoredMigration("DND_ONLY", false))
    }

    @Test
    fun pauseDurationValidationUsesFifteenMinuteSteps() {
        listOf(15, 120, 1440).forEach { assertTrue(PlaceStore.isValidPauseMinutes(it)) }
        listOf(14, 16, 1441).forEach { assertFalse(PlaceStore.isValidPauseMinutes(it)) }
    }

    @Test
    fun notificationPauseLabelsStayCompact() {
        assertEquals("45m", MonitoringNotification.formatMinutes(45))
        assertEquals("2h", MonitoringNotification.formatMinutes(120))
        assertEquals("1h 15m", MonitoringNotification.formatMinutes(75))
    }

    @Test
    fun adaptiveChecksFollowTravelTimeAndDailyCap() {
        assertEquals(
            5L * 60L * 1_000L,
            AdaptiveLocationChecks.delayForDistance(1_000f, null)
        )
        assertEquals(
            60L * 60L * 1_000L,
            AdaptiveLocationChecks.delayForDistance(12_000f, null)
        )
        assertEquals(
            AdaptiveLocationChecks.MAX_DELAY_MS,
            AdaptiveLocationChecks.delayForDistance(300_000f, null)
        )
        assertEquals(
            10L * 60L * 1_000L,
            AdaptiveLocationChecks.delayForDistance(12_000f, 20f)
        )
    }

    @Test
    fun adaptiveDelayLabelsCoverMinutesHoursAndDays() {
        assertEquals("5 minutes", formatDelay(5L * 60L * 1_000L))
        assertEquals("1 hour", formatDelay(60L * 60L * 1_000L))
        assertEquals("1h 15m", formatDelay(75L * 60L * 1_000L))
        assertEquals("1 day", formatDelay(AdaptiveLocationChecks.MAX_DELAY_MS))
    }

    @Test
    fun restorationOnlyReplacesTheValueOwnedByTheApp() {
        assertTrue(shouldRestoreSetting(original = 4, applied = 0, current = 0))
        assertFalse(shouldRestoreSetting(original = 4, applied = 0, current = 2))
        assertFalse(shouldRestoreSetting(original = 0, applied = 0, current = 0))
        assertFalse(shouldRestoreSetting(original = 4, applied = null, current = 0))
    }

    @Test
    fun newerRingerChoiceBecomesTheRestorationTarget() {
        val active = AudioSnapshot(originalRingerMode = 2, appliedRingerMode = 0)
        assertEquals(1, ringerOriginalForApply(active, current = 1))
        assertEquals(2, ringerOriginalForApply(active, current = 0))
        assertEquals(2, ringerOriginalForApply(AudioSnapshot(), current = 2))
    }
}
