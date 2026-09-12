package com.example.onetapdnd

internal interface RingerAccess {
    var mode: Int
    val hasPolicyAccess: Boolean
    val isVolumeFixed: Boolean
}

internal data class RingerUpdate(val snapshot: AudioSnapshot, val error: String? = null)

internal class RingerController(private val access: RingerAccess) {
    fun apply(snapshot: AudioSnapshot, target: RingerMode): RingerUpdate {
        val current = access.mode
        if (current != target.platformValue && !access.hasPolicyAccess) {
            return RingerUpdate(snapshot, "Allow DND access to change the ringer mode.")
        }
        if (current != target.platformValue && access.isVolumeFixed) {
            return RingerUpdate(snapshot, "This device does not allow ringer mode changes.")
        }
        val original = ringerOriginalForApply(snapshot, current)
        return try {
            if (current != target.platformValue) access.mode = target.platformValue
            val actual = access.mode
            if (actual == target.platformValue) {
                RingerUpdate(snapshot.copy(originalRingerMode = original, appliedRingerMode = actual))
            } else {
                RingerUpdate(
                    if (actual != current) snapshot.copy(
                        originalRingerMode = original, appliedRingerMode = actual
                    ) else snapshot,
                    "Android did not apply ${target.label.lowercase()} mode. Check your phone's sound settings."
                )
            }
        } catch (_: SecurityException) {
            RingerUpdate(snapshot, "Allow DND access to change the ringer mode.")
        }
    }

    fun restore(snapshot: AudioSnapshot): RingerUpdate {
        val original = snapshot.originalRingerMode
        if (original != null && shouldRestoreRingerMode(original, snapshot.appliedRingerMode, access.mode)) {
            try {
                access.mode = original
                if (access.mode != original) {
                    return RingerUpdate(snapshot, "Android did not restore the previous ringer mode. Open sound settings to retry.")
                }
            } catch (_: SecurityException) {
                return RingerUpdate(snapshot, "Allow DND access to restore the previous ringer mode.")
            }
        }
        return RingerUpdate(snapshot.copy(originalRingerMode = null, appliedRingerMode = null))
    }
}

internal fun shouldRestoreSetting(original: Int, applied: Int?, current: Int): Boolean =
    applied != null && current == applied && current != original

internal fun shouldRestoreRingerMode(original: Int, applied: Int?, current: Int): Boolean =
    shouldRestoreSetting(original, applied, current)

internal fun ringerOriginalForApply(snapshot: AudioSnapshot, current: Int): Int =
    if (snapshot.appliedRingerMode != null && current != snapshot.appliedRingerMode) {
        current
    } else {
        snapshot.originalRingerMode ?: current
    }
