package com.example.onetapdnd

enum class PlaceAudioMode(val strength: Int) {
    DND_ONLY(0),
    DND_AND_SILENT(1),
    DND_SILENT_MEDIA_ZERO(2);

    val silencesRinger: Boolean
        get() = this != DND_ONLY

    val mutesMedia: Boolean
        get() = this == DND_SILENT_MEDIA_ZERO

    companion object {
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

    fun strongestMode(rules: List<PlaceRule>): PlaceAudioMode? =
        active(rules).maxByOrNull { it.audioMode.strength }?.audioMode
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
