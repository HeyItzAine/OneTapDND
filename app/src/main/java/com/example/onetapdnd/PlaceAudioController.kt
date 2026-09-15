package com.example.onetapdnd

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager

class PlaceAudioController(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val store = PlaceStore(context)
    private var ringerRestoreTarget: Int? = null
    private var ownsRingerRestore = false
    private var ringerWritten = false
    private val ringer = RingerController(object : RingerAccess {
        override var mode: Int
            get() = audioManager.ringerMode
            set(value) {
                audioManager.ringerMode = value
                ringerWritten = true
            }
        override val hasPolicyAccess get() = notificationManager.isNotificationPolicyAccessGranted
        override val isVolumeFixed get() = audioManager.isVolumeFixed
        override val isDndActive get() = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    })

    fun prepare(mode: PlaceAudioMode?) {
        var snapshot = store.audioSnapshot()
        val current = audioManager.ringerMode
        val ownershipMode = if (current == AudioManager.RINGER_MODE_SILENT &&
            notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        ) snapshot.appliedRingerMode ?: current else current
        if (mode?.ringer != null) {
            snapshot = snapshot.copy(originalRingerMode = store.pendingRingerRestore()
                ?: ringerOriginalForApply(snapshot, ownershipMode))
            store.savePendingRingerRestore(null)
        } else {
            ownsRingerRestore = snapshot.originalRingerMode != null && snapshot.appliedRingerMode != null
            ringerRestoreTarget = store.pendingRingerRestore()
                ?: if (ownsRingerRestore && ownershipMode != snapshot.appliedRingerMode) current
                    else snapshot.originalRingerMode
            if (ownsRingerRestore) store.savePendingRingerRestore(ringerRestoreTarget)
        }
        snapshot = restoreLegacyVolumes(snapshot)
        snapshot = if (mode?.mutesMedia == true && !audioManager.isVolumeFixed) {
            snapshot.copy(originalMediaVolume = snapshot.originalMediaVolume
                ?: audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        } else {
            restoreMedia(snapshot)
        }
        store.saveAudioSnapshot(snapshot)
    }

    fun apply(mode: PlaceAudioMode?, restoreRinger: Boolean = true): Boolean {
        ringerWritten = false
        var snapshot = store.audioSnapshot()
        if (mode?.ringer != null) {
            val applied = ringer.apply(snapshot, mode.ringer, snapshot.originalRingerMode)
            snapshot = applied.snapshot
            store.audioError(applied.error)
        } else if (restoreRinger) {
            // DND can mask the underlying ringer. Decide ownership before releasing
            // the rule, then restore against the unmasked value afterward.
            val restored = ringer.restore(snapshot.copy(
                originalRingerMode = ringerRestoreTarget ?: snapshot.originalRingerMode,
                appliedRingerMode = if (ownsRingerRestore) audioManager.ringerMode else snapshot.appliedRingerMode
            ))
            snapshot = restored.snapshot
            store.audioError(restored.error)
            if (snapshot.originalRingerMode == null) store.savePendingRingerRestore(null)
        }
        if (mode?.mutesMedia == true && !audioManager.isVolumeFixed) snapshot = applyMediaZero(snapshot)
        store.saveAudioSnapshot(snapshot)
        return ringerWritten
    }

    fun recordAppliedRinger() {
        val snapshot = store.audioSnapshot()
        if (snapshot.appliedRingerMode != null) {
            store.saveAudioSnapshot(snapshot.copy(appliedRingerMode = audioManager.ringerMode))
        }
    }

    fun enforceMediaZero() {
        if (!audioManager.isVolumeFixed && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != 0) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
        }
    }

    private fun restoreLegacyVolumes(snapshot: AudioSnapshot): AudioSnapshot {
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL || audioManager.isVolumeFixed) return snapshot
        fun restore(stream: Int, original: Int?, applied: Int?) {
            if (original != null && shouldRestoreSetting(original, applied, audioManager.getStreamVolume(stream))) {
                audioManager.setStreamVolume(stream, original, 0)
                check(audioManager.getStreamVolume(stream) == original)
            }
        }
        return runCatching {
            restore(AudioManager.STREAM_RING, snapshot.originalRingVolume, snapshot.appliedRingVolume)
            restore(AudioManager.STREAM_NOTIFICATION, snapshot.originalNotificationVolume, snapshot.appliedNotificationVolume)
            restore(AudioManager.STREAM_SYSTEM, snapshot.originalSystemVolume, snapshot.appliedSystemVolume)
            snapshot.copy(
                originalRingVolume = null, appliedRingVolume = null,
                originalNotificationVolume = null, appliedNotificationVolume = null,
                originalSystemVolume = null, appliedSystemVolume = null
            )
        }.getOrElse { snapshot }
    }

    private fun applyMediaZero(snapshot: AudioSnapshot): AudioSnapshot {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val original = snapshot.originalMediaVolume ?: current
        val applied = runCatching {
            if (current != 0) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            check(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
            0
        }.getOrNull() ?: snapshot.appliedMediaVolume
        return snapshot.copy(originalMediaVolume = original, appliedMediaVolume = applied)
    }

    private fun restoreMedia(snapshot: AudioSnapshot): AudioSnapshot {
        val original = snapshot.originalMediaVolume ?: return snapshot.copy(appliedMediaVolume = null)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (shouldRestoreSetting(original, snapshot.appliedMediaVolume, current)) {
            if (audioManager.isVolumeFixed) return snapshot
            val restored = runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == original
            }.getOrDefault(false)
            if (!restored) return snapshot
        }
        return snapshot.copy(originalMediaVolume = null, appliedMediaVolume = null)
    }
}
