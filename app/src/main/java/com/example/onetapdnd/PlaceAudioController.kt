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

    private fun applyRingerSilence(snapshot: AudioSnapshot): AudioSnapshot {
        val current = audioManager.ringerMode
        val original = ringerOriginalForApply(snapshot, current)
        val applied = runCatching {
            if (current != AudioManager.RINGER_MODE_SILENT) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
            AudioManager.RINGER_MODE_SILENT
        }.getOrNull() ?: snapshot.appliedRingerMode
        return snapshot.copy(originalRingerMode = original, appliedRingerMode = applied)
    }

    private fun restoreRinger(snapshot: AudioSnapshot): AudioSnapshot {
        val original = snapshot.originalRingerMode ?: return snapshot.copy(appliedRingerMode = null)
        val applied = snapshot.appliedRingerMode
        val current = audioManager.ringerMode
        if (shouldRestoreSetting(original, applied, current)) {
            val restored = runCatching {
                audioManager.ringerMode = original
                true
            }.getOrDefault(false)
            if (!restored) return snapshot
        }
        return snapshot.copy(originalRingerMode = null, appliedRingerMode = null)
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
}

internal fun shouldRestoreSetting(original: Int, applied: Int?, current: Int): Boolean =
    applied != null && current == applied && current != original

internal fun ringerOriginalForApply(snapshot: AudioSnapshot, current: Int): Int =
    if (snapshot.appliedRingerMode != null && current != snapshot.appliedRingerMode) {
        current
    } else {
        snapshot.originalRingerMode ?: current
    }
