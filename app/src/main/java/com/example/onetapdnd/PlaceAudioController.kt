package com.example.onetapdnd

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager

class PlaceAudioController(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val store = PlaceStore(context)
    private val ringer = RingerController(object : RingerAccess {
        override var mode: Int
            get() = audioManager.ringerMode
            set(value) { audioManager.ringerMode = value }
        override val hasPolicyAccess get() = notificationManager.isNotificationPolicyAccessGranted
        override val isVolumeFixed get() = audioManager.isVolumeFixed
    })

    fun reconcile(mode: PlaceAudioMode?) {
        val snapshot = store.audioSnapshot()
        val update = if (mode?.ringer != null) {
            ringer.apply(snapshot, mode.ringer)
        } else {
            ringer.restore(snapshot)
        }
        store.saveAudioSnapshot(update.snapshot)
        store.audioError(update.error)
        var next = restoreLegacyVolumes(update.snapshot)
        next = if (mode?.mutesMedia == true && !audioManager.isVolumeFixed) {
            applyMediaZero(next)
        } else {
            restoreMedia(next)
        }
        store.saveAudioSnapshot(next)
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
