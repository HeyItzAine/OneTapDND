package com.example.onetapdnd

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class PlaceStore(context: Context) {
    val preferences = context.getSharedPreferences("quiet_places", Context.MODE_PRIVATE)

    init {
        migrateGlobalState()
    }

    fun rules(): List<PlaceRule> = runCatching {
        val array = JSONArray(preferences.getString(KEY_RULES, "[]"))
        val parsed = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PlaceRule(
                id = item.getString("id"),
                name = item.getString("name"),
                latitude = item.getDouble("latitude"),
                longitude = item.getDouble("longitude"),
                radiusMeters = item.getDouble("radius").toFloat(),
                audioMode = PlaceAudioMode.fromStored(
                    item.optString("audioMode").takeIf { it.isNotBlank() },
                    item.optBoolean("silence")
                ),
                enabled = item.optBoolean("enabled", true)
            )
        }.filter { it.isValid() }
        if ((0 until array.length()).any {
                val item = array.getJSONObject(it)
                PlaceAudioMode.requiresStoredMigration(
                    item.optString("audioMode").takeIf(String::isNotBlank),
                    item.has("silence")
                )
            }
        ) {
            save(parsed)
        }
        parsed
    }.getOrDefault(emptyList())

    fun save(rules: List<PlaceRule>) {
        require(rules.size <= 20 && rules.all { it.isValid() })
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject().put("id", rule.id).put("name", rule.name)
                    .put("latitude", rule.latitude).put("longitude", rule.longitude)
                    .put("radius", rule.radiusMeters).put("audioMode", rule.audioMode.name)
                    .put("enabled", rule.enabled)
            )
        }
        val enabledIds = rules.filter { it.enabled }.map { it.id }.toSet()
        val state = state()
        preferences.edit().putString(KEY_RULES, array.toString())
            .putStringSet(KEY_INSIDE, state.inside intersect enabledIds).commit()
    }

    fun state() = PlaceState(preferences.getStringSet(KEY_INSIDE, emptySet())!!.toSet())

    fun saveState(state: PlaceState) {
        preferences.edit().putStringSet(KEY_INSIDE, state.inside).commit()
    }

    fun clearPosition() {
        saveState(state().copy(inside = emptySet()))
    }

    fun pauseUntilEpochMs(): Long = preferences.getLong(KEY_PAUSED_UNTIL, 0L)

    fun isPaused(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        pauseUntilEpochMs() > nowEpochMs

    fun savePauseUntilEpochMs(untilEpochMs: Long) {
        preferences.edit().putLong(KEY_PAUSED_UNTIL, untilEpochMs.coerceAtLeast(0L)).commit()
    }

    fun clearExpiredPause(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val deadline = pauseUntilEpochMs()
        if (deadline == 0L || deadline > nowEpochMs) return false
        preferences.edit().remove(KEY_PAUSED_UNTIL).commit()
        return true
    }

    fun customPauseMinutes(): Int = preferences
        .getInt(KEY_CUSTOM_PAUSE_MINUTES, DEFAULT_PAUSE_MINUTES)
        .takeIf(::isValidPauseMinutes) ?: DEFAULT_PAUSE_MINUTES

    fun saveCustomPauseMinutes(minutes: Int) {
        require(isValidPauseMinutes(minutes))
        preferences.edit().putInt(KEY_CUSTOM_PAUSE_MINUTES, minutes).commit()
    }

    fun audioSnapshot() = AudioSnapshot(
        originalRingerMode = preferences.optionalInt(KEY_ORIGINAL_RINGER),
        appliedRingerMode = preferences.optionalInt(KEY_APPLIED_RINGER),
        originalMediaVolume = preferences.optionalInt(KEY_ORIGINAL_MEDIA),
        appliedMediaVolume = preferences.optionalInt(KEY_APPLIED_MEDIA),
        originalRingVolume = preferences.optionalInt(KEY_ORIGINAL_RING_VOL),
        appliedRingVolume = preferences.optionalInt(KEY_APPLIED_RING_VOL),
        originalNotificationVolume = preferences.optionalInt(KEY_ORIGINAL_NOTIF_VOL),
        appliedNotificationVolume = preferences.optionalInt(KEY_APPLIED_NOTIF_VOL),
        originalSystemVolume = preferences.optionalInt(KEY_ORIGINAL_SYSTEM_VOL),
        appliedSystemVolume = preferences.optionalInt(KEY_APPLIED_SYSTEM_VOL)
    )

    fun saveAudioSnapshot(snapshot: AudioSnapshot) {
        preferences.edit()
            .putOptionalInt(KEY_ORIGINAL_RINGER, snapshot.originalRingerMode)
            .putOptionalInt(KEY_APPLIED_RINGER, snapshot.appliedRingerMode)
            .putOptionalInt(KEY_ORIGINAL_MEDIA, snapshot.originalMediaVolume)
            .putOptionalInt(KEY_APPLIED_MEDIA, snapshot.appliedMediaVolume)
            .putOptionalInt(KEY_ORIGINAL_RING_VOL, snapshot.originalRingVolume)
            .putOptionalInt(KEY_APPLIED_RING_VOL, snapshot.appliedRingVolume)
            .putOptionalInt(KEY_ORIGINAL_NOTIF_VOL, snapshot.originalNotificationVolume)
            .putOptionalInt(KEY_APPLIED_NOTIF_VOL, snapshot.appliedNotificationVolume)
            .putOptionalInt(KEY_ORIGINAL_SYSTEM_VOL, snapshot.originalSystemVolume)
            .putOptionalInt(KEY_APPLIED_SYSTEM_VOL, snapshot.appliedSystemVolume)
            .commit()
    }

    fun status(message: String) {
        preferences.edit().putString(KEY_STATUS, message).commit()
    }

    fun status(): String = preferences.getString(KEY_STATUS, "Add a place to get started.")!!

    fun adaptiveCheckStatus(): String = preferences.getString(KEY_ADAPTIVE_STATUS, "")!!

    fun saveAdaptiveCheckStatus(message: String, nextCheckEpochMs: Long) {
        preferences.edit()
            .putString(KEY_ADAPTIVE_STATUS, message)
            .putLong(KEY_NEXT_LOCATION_CHECK, nextCheckEpochMs.coerceAtLeast(0L))
            .commit()
    }

    fun clearAdaptiveCheckStatus() {
        preferences.edit()
            .remove(KEY_ADAPTIVE_STATUS)
            .remove(KEY_NEXT_LOCATION_CHECK)
            .commit()
    }

    fun nextLocationCheckEpochMs(): Long = preferences.getLong(KEY_NEXT_LOCATION_CHECK, 0L)

    private fun migrateGlobalState() {
        if (preferences.getInt(KEY_STATE_VERSION, 0) >= STATE_VERSION) return
        preferences.edit().remove("paused").putInt(KEY_STATE_VERSION, STATE_VERSION).commit()
    }

    companion object {
        const val DEFAULT_PAUSE_MINUTES = 120
        const val MIN_PAUSE_MINUTES = 15
        const val MAX_PAUSE_MINUTES = 1440
        const val PAUSE_STEP_MINUTES = 15

        fun isValidPauseMinutes(minutes: Int): Boolean =
            minutes in MIN_PAUSE_MINUTES..MAX_PAUSE_MINUTES && minutes % PAUSE_STEP_MINUTES == 0

        private const val STATE_VERSION = 2
        private const val KEY_STATE_VERSION = "state_version"
        private const val KEY_RULES = "rules"
        private const val KEY_INSIDE = "inside"
        private const val KEY_PAUSED_UNTIL = "pausedUntilEpochMs"
        private const val KEY_CUSTOM_PAUSE_MINUTES = "customPauseMinutes"
        private const val KEY_ORIGINAL_RINGER = "audioOriginalRingerMode"
        private const val KEY_APPLIED_RINGER = "audioAppliedRingerMode"
        private const val KEY_ORIGINAL_MEDIA = "audioOriginalMediaVolume"
        private const val KEY_APPLIED_MEDIA = "audioAppliedMediaVolume"
        private const val KEY_ORIGINAL_RING_VOL = "audioOriginalRingVolume"
        private const val KEY_APPLIED_RING_VOL = "audioAppliedRingVolume"
        private const val KEY_ORIGINAL_NOTIF_VOL = "audioOriginalNotificationVolume"
        private const val KEY_APPLIED_NOTIF_VOL = "audioAppliedNotificationVolume"
        private const val KEY_ORIGINAL_SYSTEM_VOL = "audioOriginalSystemVolume"
        private const val KEY_APPLIED_SYSTEM_VOL = "audioAppliedSystemVolume"
        private const val KEY_STATUS = "status"
        private const val KEY_ADAPTIVE_STATUS = "adaptiveCheckStatus"
        private const val KEY_NEXT_LOCATION_CHECK = "nextLocationCheckEpochMs"
    }
}

private fun SharedPreferences.optionalInt(key: String): Int? =
    if (contains(key)) getInt(key, 0) else null

private fun SharedPreferences.Editor.putOptionalInt(
    key: String,
    value: Int?
): SharedPreferences.Editor = if (value == null) remove(key) else putInt(key, value)
