package com.example.onetapdnd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PlaceStore(context: Context) {
    val preferences = context.getSharedPreferences("quiet_places", Context.MODE_PRIVATE)

    fun rules(): List<PlaceRule> = runCatching {
        val array = JSONArray(preferences.getString("rules", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PlaceRule(item.getString("id"), item.getString("name"),
                item.getDouble("latitude"), item.getDouble("longitude"),
                item.getDouble("radius").toFloat(), item.optBoolean("silence"),
                item.optBoolean("enabled", true))
        }.filter { it.isValid() }
    }.getOrDefault(emptyList())

    fun save(rules: List<PlaceRule>) {
        require(rules.size <= 20 && rules.all { it.isValid() })
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(JSONObject().put("id", rule.id).put("name", rule.name)
                .put("latitude", rule.latitude).put("longitude", rule.longitude)
                .put("radius", rule.radiusMeters).put("silence", rule.totalSilence)
                .put("enabled", rule.enabled))
        }
        val enabledIds = rules.filter { it.enabled }.map { it.id }.toSet()
        val state = state()
        // Finish persisting before Android can stop a receiver's process.
        preferences.edit().putString("rules", array.toString())
            .putStringSet("inside", state.inside intersect enabledIds)
            .putStringSet("paused", state.paused intersect enabledIds).commit()
    }

    fun state() = PlaceState(
        preferences.getStringSet("inside", emptySet())!!.toSet(),
        preferences.getStringSet("paused", emptySet())!!.toSet())

    fun saveState(state: PlaceState) {
        preferences.edit().putStringSet("inside", state.inside)
            .putStringSet("paused", state.paused).commit()
    }

    fun clearPosition() { saveState(state().copy(inside = emptySet())) }

    fun status(message: String) { preferences.edit().putString("status", message).commit() }
    fun status(): String = preferences.getString("status", "Add a place to get started.")!!
}
