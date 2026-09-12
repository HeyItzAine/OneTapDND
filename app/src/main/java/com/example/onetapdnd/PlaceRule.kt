package com.example.onetapdnd

enum class RingerMode(val platformValue: Int, val quietness: Int, val label: String) {
    SILENT(0, 3, "Silent"),
    VIBRATE(1, 2, "Vibrate"),
    SOUND(2, 1, "Sound")
}

enum class PlaceAudioMode(val ringer: RingerMode?, val mutesMedia: Boolean = false) {
    DND_ONLY(null),
    DND_AND_SILENT(RingerMode.SILENT),
    DND_SILENT_MEDIA_ZERO(RingerMode.SILENT, true),
    DND_AND_VIBRATE(RingerMode.VIBRATE),
    DND_AND_SOUND(RingerMode.SOUND),
    DND_VIBRATE_MEDIA_ZERO(RingerMode.VIBRATE, true),
    DND_SOUND_MEDIA_ZERO(RingerMode.SOUND, true),
    DND_MEDIA_ZERO(null, true);

    companion object {
        fun fromSettings(ringer: RingerMode?, mutesMedia: Boolean): PlaceAudioMode =
            entries.first { it.ringer == ringer && it.mutesMedia == mutesMedia }

        fun fromStored(value: String?, legacySilence: Boolean): PlaceAudioMode =
            entries.firstOrNull { it.name == value }
                ?: if (legacySilence) DND_AND_SILENT else DND_ONLY

        fun requiresStoredMigration(value: String?, hasLegacySilenceKey: Boolean): Boolean =
            hasLegacySilenceKey || entries.none { it.name == value }
    }
}

data class PlaceRule(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val audioMode: PlaceAudioMode = PlaceAudioMode.DND_ONLY,
    val enabled: Boolean = true
) {
    fun isValid(): Boolean = id.isNotBlank() && name.isNotBlank() &&
        latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        radiusMeters.isFinite() && radiusMeters in 100f..10000f
}

data class PlaceState(val inside: Set<String>) {
    fun transition(ids: Set<String>, entering: Boolean): PlaceState =
        if (entering) copy(inside = inside + ids) else copy(inside = inside - ids)

    fun active(rules: List<PlaceRule>): List<PlaceRule> =
        rules.filter { it.enabled && it.id in inside }

    fun strongestMode(rules: List<PlaceRule>): PlaceAudioMode? {
        val modes = active(rules).map { it.audioMode }
        if (modes.isEmpty()) return null
        return PlaceAudioMode.fromSettings(
            modes.mapNotNull { it.ringer }.maxByOrNull { it.quietness },
            modes.any { it.mutesMedia }
        )
    }
}

data class AudioSnapshot(
    val originalRingerMode: Int? = null,
    val appliedRingerMode: Int? = null,
    val originalMediaVolume: Int? = null,
    val appliedMediaVolume: Int? = null,
    val originalRingVolume: Int? = null,
    val appliedRingVolume: Int? = null,
    val originalNotificationVolume: Int? = null,
    val appliedNotificationVolume: Int? = null,
    val originalSystemVolume: Int? = null,
    val appliedSystemVolume: Int? = null
)
