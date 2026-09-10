package com.example.onetapdnd

import android.content.Context
import android.media.AudioManager

class PlaceAudioController(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val store = PlaceStore(context)

    fun reconcile(mode: PlaceAudioMode?) {
        var snapshot = store.audioSnapshot()
        snapshot = if (mode?.silencesRinger == true) {
            applyRingerSilence(snapshot)
        } else {
            restoreRinger(snapshot)
        }
        snapshot = if (mode?.mutesMedia == true && !audioManager.isVolumeFixed) {
            applyMediaZero(snapshot)
        } else {
            restoreMedia(snapshot)
        }
        store.saveAudioSnapshot(snapshot)
    }

    fun enforceMediaZero() {
        if (!audioManager.isVolumeFixed &&
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != 0
        ) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
        }
    }

    fun enforceRingerSilence() {
        runCatching {
            silenceRingerStreams()
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
        }
    }

    private fun silenceRingerStreams() {
        RINGER_STREAMS.forEach { stream ->
            runCatching {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            }
            if (!audioManager.isVolumeFixed) {
                runCatching {
                    if (audioManager.getStreamVolume(stream) != 0) {
                        audioManager.setStreamVolume(stream, 0, 0)
                    }
                }
            }
        }
    }

    private fun applyRingerSilence(snapshot: AudioSnapshot): AudioSnapshot {
        val currentRinger = audioManager.ringerMode
        val originalRinger = ringerOriginalForApply(snapshot, currentRinger)

        val currentRingVol = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_RING) }.getOrNull()
        val originalRingVol = snapshot.originalRingVolume ?: currentRingVol

        val currentNotifVol = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrNull()
        val originalNotifVol = snapshot.originalNotificationVolume ?: currentNotifVol

        val currentSystemVol = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM) }.getOrNull()
        val originalSystemVol = snapshot.originalSystemVolume ?: currentSystemVol

        val appliedRinger = runCatching {
            silenceRingerStreams()
            if (currentRinger != AudioManager.RINGER_MODE_SILENT) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
            AudioManager.RINGER_MODE_SILENT
        }.getOrNull() ?: snapshot.appliedRingerMode

        return snapshot.copy(
            originalRingerMode = originalRinger,
            appliedRingerMode = appliedRinger,
            originalRingVolume = originalRingVol,
            appliedRingVolume = if (appliedRinger != null) 0 else snapshot.appliedRingVolume,
            originalNotificationVolume = originalNotifVol,
            appliedNotificationVolume = if (appliedRinger != null) 0 else snapshot.appliedNotificationVolume,
            originalSystemVolume = originalSystemVol,
            appliedSystemVolume = if (appliedRinger != null) 0 else snapshot.appliedSystemVolume
        )
    }

    private fun restoreRinger(snapshot: AudioSnapshot): AudioSnapshot {
        val originalRinger = snapshot.originalRingerMode
        val appliedRinger = snapshot.appliedRingerMode

        if (originalRinger == null &&
            snapshot.originalRingVolume == null &&
            snapshot.originalNotificationVolume == null &&
            snapshot.originalSystemVolume == null
        ) {
            return snapshot.copy(
                appliedRingerMode = null,
                appliedRingVolume = null,
                appliedNotificationVolume = null,
                appliedSystemVolume = null
            )
        }

        val currentRinger = audioManager.ringerMode

        val restored = runCatching {
            restoreStreamVolume(AudioManager.STREAM_RING, snapshot.originalRingVolume, snapshot.appliedRingVolume)
            restoreStreamVolume(AudioManager.STREAM_NOTIFICATION, snapshot.originalNotificationVolume, snapshot.appliedNotificationVolume)
            restoreStreamVolume(AudioManager.STREAM_SYSTEM, snapshot.originalSystemVolume, snapshot.appliedSystemVolume)

            if (originalRinger != null && shouldRestoreRingerMode(originalRinger, appliedRinger, currentRinger)) {
                audioManager.ringerMode = originalRinger
            }
            true
        }.getOrDefault(false)

        if (!restored) return snapshot

        return snapshot.copy(
            originalRingerMode = null,
            appliedRingerMode = null,
            originalRingVolume = null,
            appliedRingVolume = null,
            originalNotificationVolume = null,
            appliedNotificationVolume = null,
            originalSystemVolume = null,
            appliedSystemVolume = null
        )
    }

    private fun restoreStreamVolume(stream: Int, original: Int?, applied: Int?) {
        runCatching {
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            if (original != null && !audioManager.isVolumeFixed) {
                val current = audioManager.getStreamVolume(stream)
                if (shouldRestoreSetting(original, applied, current)) {
                    audioManager.setStreamVolume(stream, original, 0)
                }
            }
        }
    }

    private fun applyMediaZero(snapshot: AudioSnapshot): AudioSnapshot {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val original = snapshot.originalMediaVolume ?: current
        val applied = runCatching {
            if (current != 0) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            0
        }.getOrNull() ?: snapshot.appliedMediaVolume
        return snapshot.copy(originalMediaVolume = original, appliedMediaVolume = applied)
    }

    private fun restoreMedia(snapshot: AudioSnapshot): AudioSnapshot {
        val original = snapshot.originalMediaVolume ?: return snapshot.copy(appliedMediaVolume = null)
        val applied = snapshot.appliedMediaVolume
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (shouldRestoreSetting(original, applied, current) && !audioManager.isVolumeFixed) {
            val restored = runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
                true
            }.getOrDefault(false)
            if (!restored) return snapshot
        }
        return snapshot.copy(originalMediaVolume = null, appliedMediaVolume = null)
    }

    companion object {
        val RINGER_STREAMS = intArrayOf(
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM
        )
    }
}

internal fun shouldRestoreSetting(original: Int, applied: Int?, current: Int): Boolean =
    applied != null && current == applied && current != original

internal fun shouldRestoreRingerMode(original: Int, applied: Int?, current: Int): Boolean =
    shouldRestoreSetting(original, applied, current) ||
        (applied == AudioManager.RINGER_MODE_SILENT &&
            current == AudioManager.RINGER_MODE_VIBRATE &&
            original == AudioManager.RINGER_MODE_NORMAL)

internal fun ringerOriginalForApply(snapshot: AudioSnapshot, current: Int): Int =
    if (snapshot.appliedRingerMode != null && current != snapshot.appliedRingerMode) {
        current
    } else {
        snapshot.originalRingerMode ?: current
    }
