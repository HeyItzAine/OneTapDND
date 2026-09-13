package com.example.onetapdnd

import org.junit.Assert.*
import org.junit.Test

class RingerControllerTest {
    private class FakeRinger(initial: Int) : RingerAccess {
        var actual = initial
        var reject = false
        var throwOnWrite = false
        val writes = mutableListOf<Int>()
        override var hasPolicyAccess = true
        override var isVolumeFixed = false
        override var mode: Int
            get() = actual
            set(value) {
                writes.add(value)
                if (throwOnWrite) throw SecurityException()
                if (!reject) actual = value
            }
    }

    @Test
    fun allNineTransitionsApplyDirectlyAndRestore() {
        RingerMode.entries.forEach { original ->
            RingerMode.entries.forEach { target ->
                val access = FakeRinger(original.platformValue)
                val controller = RingerController(access)
                val applied = controller.apply(AudioSnapshot(), target)
                assertNull(applied.error)
                assertEquals(target.platformValue, access.mode)
                assertEquals(original.platformValue, applied.snapshot.originalRingerMode)
                assertEquals(when {
                    target == RingerMode.SILENT -> listOf(2, 0)
                    original == target -> emptyList<Int>()
                    else -> listOf(target.platformValue)
                }, access.writes)
                val restored = controller.restore(applied.snapshot)
                assertNull(restored.error)
                assertEquals(original.platformValue, access.mode)
                assertNull(restored.snapshot.originalRingerMode)
            }
        }
    }

    @Test
    fun switchingBetweenActivePlacesKeepsOriginalMode() {
        val access = FakeRinger(2)
        val controller = RingerController(access)
        val silent = controller.apply(AudioSnapshot(), RingerMode.SILENT)
        val vibrate = controller.apply(silent.snapshot, RingerMode.VIBRATE)
        assertEquals(2, vibrate.snapshot.originalRingerMode)
        controller.restore(vibrate.snapshot)
        assertEquals(2, access.mode)
    }

    @Test
    fun dndSideEffectCannotReplaceTheOriginalVibrateSetting() {
        val access = FakeRinger(0)
        val controller = RingerController(access)
        val captured = AudioSnapshot(originalRingerMode = 1)
        val applied = controller.apply(captured, RingerMode.SILENT, originalBeforeDnd = 1)
        assertEquals(1, applied.snapshot.originalRingerMode)
        controller.restore(applied.snapshot)
        assertEquals(1, access.mode)
    }

    @Test
    fun manualChangeIsPreservedOnExit() {
        val access = FakeRinger(2)
        val controller = RingerController(access)
        val silent = controller.apply(AudioSnapshot(), RingerMode.SILENT)
        access.actual = 1
        controller.restore(silent.snapshot)
        assertEquals(1, access.mode)
    }

    @Test
    fun rejectedChangeDoesNotClaimSuccessOrOwnership() {
        val access = FakeRinger(2).apply { reject = true }
        val result = RingerController(access).apply(AudioSnapshot(), RingerMode.SILENT)
        assertNotNull(result.error)
        assertEquals(AudioSnapshot(), result.snapshot)
        assertEquals(listOf(2, 0), access.writes)
    }

    @Test
    fun missingPermissionAndFixedVolumeDoNotAttemptChanges() {
        listOf(
            FakeRinger(2).apply { hasPolicyAccess = false },
            FakeRinger(2).apply { isVolumeFixed = true }
        ).forEach { access ->
            val result = RingerController(access).apply(AudioSnapshot(), RingerMode.SILENT)
            assertNotNull(result.error)
            assertTrue(access.writes.isEmpty())
            assertNull(result.snapshot.appliedRingerMode)
        }
    }

    @Test
    fun permissionRevocationDuringWriteIsReported() {
        val access = FakeRinger(2).apply { throwOnWrite = true }
        val result = RingerController(access).apply(AudioSnapshot(), RingerMode.SILENT)
        assertNotNull(result.error)
        assertNull(result.snapshot.appliedRingerMode)
    }

    @Test
    fun rejectedRestoreRetainsSnapshotForRetry() {
        val access = FakeRinger(2)
        val controller = RingerController(access)
        val applied = controller.apply(AudioSnapshot(), RingerMode.SILENT)
        access.reject = true
        val failed = controller.restore(applied.snapshot)
        assertNotNull(failed.error)
        assertEquals(applied.snapshot, failed.snapshot)
        access.reject = false
        val retried = controller.restore(failed.snapshot)
        assertNull(retried.error)
        assertEquals(2, access.mode)
    }

    @Test fun silentReadWithoutAnAppliedSilentRequestStillWritesTheRinger() {
        val access = FakeRinger(0)
        val result = RingerController(access).apply(AudioSnapshot(originalRingerMode = 2), RingerMode.SILENT)
        assertEquals(listOf(2, 0), access.writes)
        assertEquals(0, result.snapshot.requestedRingerMode)
        assertEquals(2, result.snapshot.originalRingerMode)
        access.writes.clear()
        RingerController(access).apply(result.snapshot, RingerMode.SILENT)
        assertTrue("repeated checks do not pulse the ringer", access.writes.isEmpty())
    }

    @Test fun switchingFromDndMaskedSoundToSilentIsNotSkipped() {
        val access = FakeRinger(0)
        val snapshot = AudioSnapshot(originalRingerMode = 1, appliedRingerMode = 0, requestedRingerMode = 2)
        val result = RingerController(access).apply(snapshot, RingerMode.SILENT)
        assertEquals(listOf(2, 0), access.writes)
        assertEquals(1, result.snapshot.originalRingerMode)
    }
}
