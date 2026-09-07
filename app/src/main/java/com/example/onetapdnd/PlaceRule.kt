package com.example.onetapdnd

data class PlaceRule(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val totalSilence: Boolean = false,
    val enabled: Boolean = true
) {
    fun isValid(): Boolean = id.isNotBlank() && name.isNotBlank() &&
        latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        radiusMeters.isFinite() && radiusMeters in 100f..10000f
}

data class PlaceState(val inside: Set<String>, val paused: Set<String>) {
    fun transition(ids: Set<String>, entering: Boolean): PlaceState =
        if (entering) copy(inside = inside + ids)
        else copy(inside = inside - ids, paused = paused - ids)

    fun active(rules: List<PlaceRule>): List<PlaceRule> =
        rules.filter { it.enabled && it.id in inside && it.id !in paused }
}
