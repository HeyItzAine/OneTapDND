package com.example.onetapdnd

import org.junit.Assert.*
import org.junit.Test

class PlaceLocationStateTest {
    @Test fun confidentExitAndEntryChangeMembership() {
        assertFalse(insideAfterLocation(true, 240f, 200f, 10f))
        assertTrue(insideAfterLocation(false, 150f, 200f, 10f))
    }

    @Test fun uncertainBoundaryRetainsThePreviousState() {
        assertTrue(insideAfterLocation(true, 210f, 200f, 10f))
        assertFalse(insideAfterLocation(false, 190f, 200f, 10f))
        assertTrue(insideAfterLocation(true, 260f, 200f, 80f))
        assertFalse(insideAfterLocation(false, 130f, 200f, 80f))
    }

    @Test fun invalidReadingsDoNotInventAnExitOrEntry() {
        assertTrue(insideAfterLocation(true, Float.NaN, 200f, 10f))
        assertFalse(insideAfterLocation(false, 0f, 200f, Float.POSITIVE_INFINITY))
    }

    @Test fun largePlacesStillGetFrequentExitChecks() {
        val boundary = distanceToPlaceBoundary(9_000f, 10_000f, 25f)
        assertEquals(0f, boundary, 0f)
        assertEquals(AdaptiveLocationChecks.MIN_DELAY_MS, AdaptiveLocationChecks.delayForDistance(boundary, null))
        assertEquals(975f, distanceToPlaceBoundary(11_000f, 10_000f, 25f), 0f)
    }

    @Test fun staleAndOutOfOrderSamplesCannotUndoAnExit() {
        val now = 500_000_000_000L
        assertTrue(acceptsLocationSample(now - 1, now - 2, now))
        assertFalse(acceptsLocationSample(now - 2, now - 1, now))
        assertFalse(acceptsLocationSample(now - MAX_LOCATION_AGE_NANOS - 1, 0, now))
        assertFalse(acceptsLocationSample(now + 1, 0, now))
        assertFalse(acceptsLocationSample(0, 0, now))
        assertTrue(acceptsLocationSample(now - 1, now + 1, now))
    }
}
