package com.example.onetapdnd

import org.junit.Assert.*
import org.junit.Test

class PlaceStateTest {
    private val first = PlaceRule("first", "First", 0.0, 0.0, 200f)
    private val second = PlaceRule("second", "Second", 1.0, 1.0, 300f, totalSilence = true)

    @Test fun rejectsInvalidCoordinatesAndRadii() {
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

    @Test fun leavingOneOverlappingPlaceKeepsTheOtherActive() {
        val state = PlaceState(emptySet(), emptySet()).transition(setOf("first", "second"), true)
            .transition(setOf("first"), false)
        assertEquals(listOf(second), state.active(listOf(first, second)))
    }

    @Test fun tilePauseSurvivesDuplicateEnterUntilExitAndReentry() {
        val paused = PlaceState(setOf("first"), setOf("first"))
        assertTrue(paused.transition(setOf("first"), true).active(listOf(first)).isEmpty())
        val reentered = paused.transition(setOf("first"), false).transition(setOf("first"), true)
        assertEquals(listOf(first), reentered.active(listOf(first)))
    }

    @Test fun pausedPlaceDoesNotBlockArrivalAtAnotherPlace() {
        val state = PlaceState(setOf("first"), setOf("first")).transition(setOf("second"), true)
        assertEquals(listOf(second), state.active(listOf(first, second)))
    }

    @Test fun losingPositionDoesNotCancelAPause() {
        val lostPosition = PlaceState(setOf("first"), setOf("first")).copy(inside = emptySet())
        assertTrue(lostPosition.transition(setOf("first"), true).active(listOf(first)).isEmpty())
        assertTrue(lostPosition.transition(setOf("first"), false).paused.isEmpty())
    }

    @Test fun disabledAndDeletedPlacesCannotActivate() {
        val state = PlaceState(setOf("first", "deleted"), emptySet())
        assertTrue(state.active(listOf(first.copy(enabled = false))).isEmpty())
    }

    @Test fun duplicateAndUnknownExitIsHarmless() {
        val initial = PlaceState(setOf("first"), emptySet())
        assertEquals(initial, initial.transition(setOf("unknown"), false))
        assertEquals(PlaceState(emptySet(), emptySet()),
            initial.transition(setOf("first"), false).transition(setOf("first"), false))
    }
}
