package com.example.onetapdnd

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.Calendar
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class AutomationMode(val label: String) {
    TIME("Time based"), LOCATION("Location based"), HYBRID("Hybrid");
    val usesLocation get() = this != TIME
    val usesTime get() = this != LOCATION
}

data class DailyWindow(val startMinute: Int, val durationMinutes: Int) {
    init { require(startMinute in 0..1439 && durationMinutes in 1..1439) }

    fun boundaries(now: Long): List<Pair<Long, Long>> = (-1..1).map { day ->
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DATE, day)
            set(Calendar.HOUR_OF_DAY, startMinute / 60)
            set(Calendar.MINUTE, startMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        start to start + durationMinutes * 60_000L
    }

    fun active(now: Long) = boundaries(now).any { (start, end) -> now >= start && now < end }
    fun nextBoundary(now: Long) = boundaries(now).flatMap { listOf(it.first, it.second) }.filter { it > now }.min()
}

data class DailyQuietTime(
    val id: String = UUID.randomUUID().toString(),
    val window: DailyWindow,
    val enabled: Boolean = true
) {
    init { require(id.isNotBlank()) }
}

data class DailySchedule(val times: List<DailyQuietTime>) {
    fun active(now: Long) = times.any { it.enabled && it.window.active(now) }
    fun nextBoundary(now: Long): Long? = times.filter { it.enabled }.minOfOrNull { it.window.nextBoundary(now) }
}

fun legacyQuietTimes(hasSavedWindow: Boolean, startMinute: Int, durationMinutes: Int): List<DailyQuietTime> =
    if (hasSavedWindow) listOf(DailyQuietTime("legacy", DailyWindow(startMinute, durationMinutes))) else emptyList()

class AutomationSettings(private val context: Context) {
    val preferences = context.getSharedPreferences("automation", Context.MODE_PRIVATE)
    var mode: AutomationMode
        get() = AutomationMode.entries.firstOrNull { it.name == preferences.getString("mode", null) } ?: AutomationMode.LOCATION
        set(value) { preferences.edit().putString("mode", value.name).apply() }
    var enabled: Boolean
        get() = preferences.getBoolean("time_enabled", false)
        set(value) {
            if (!preferences.contains("quiet_times")) quietTimes = quietTimes
            preferences.edit().putBoolean("time_enabled", value).apply()
        }
    var quietTimes: List<DailyQuietTime>
        get() {
            val saved = preferences.getString("quiet_times", null)
            if (saved != null) return runCatching {
                val array = JSONArray(saved)
                (0 until array.length()).mapNotNull { index ->
                    runCatching {
                        val item = array.getJSONObject(index)
                        DailyQuietTime(item.getString("id"), DailyWindow(item.getInt("start"), item.getInt("duration")), item.optBoolean("enabled", true))
                    }.getOrNull()
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
            return legacyQuietTimes(
                preferences.contains("start") || preferences.contains("duration") || preferences.contains("time_enabled"),
                preferences.getInt("start", 16 * 60).coerceIn(0, 1439),
                preferences.getInt("duration", 30).coerceIn(1, 1439)
            )
        }
        set(value) {
            require(value.map { it.id }.distinct().size == value.size)
            val array = JSONArray()
            value.forEach { time ->
                array.put(JSONObject().put("id", time.id).put("start", time.window.startMinute)
                    .put("duration", time.window.durationMinutes).put("enabled", time.enabled))
            }
            preferences.edit().putString("quiet_times", array.toString()).apply()
        }
    var profilesEnabled: Boolean
        get() = preferences.getBoolean("profiles_enabled", false)
        set(value) { preferences.edit().putBoolean("profiles_enabled", value).apply() }

    fun profile(headphones: Boolean): PlaceAudioMode = PlaceAudioMode.fromStored(
        preferences.getString(if (headphones) "headphones" else "speaker", null), false
    ).let { if (!headphones && !preferences.contains("speaker")) PlaceAudioMode.DND_SILENT_MEDIA_ZERO else it }

    fun saveProfile(headphones: Boolean, value: PlaceAudioMode) {
        preferences.edit().putString(if (headphones) "headphones" else "speaker", value.name).apply()
    }

    fun headphonesConnected(): Boolean = context.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
        }

    fun requestedAudioMode(now: Long = System.currentTimeMillis()): PlaceAudioMode? {
        val store = PlaceStore(context)
        if (store.isPaused() || !DndController(context).hasAccess) return null
        val location = if (mode.usesLocation && PlaceMonitoring.hasPreciseLocation(context) &&
            PlaceMonitoring.hasBackgroundLocation(context) && PlaceMonitoring.locationEnabled(context))
            store.state().strongestMode(store.rules()) else null
        val time = mode.usesTime && enabled && DailySchedule(quietTimes).active(now)
        if (location == null && !time) return null
        return if (profilesEnabled) profile(headphonesConnected()) else location ?: PlaceAudioMode.DND_ONLY
    }

    fun schedule() {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 42, Intent(context, AutomationReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(intent)
        if (!mode.usesTime || !enabled) return
        val next = DailySchedule(quietTimes).nextBoundary(System.currentTimeMillis()) ?: return
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
                return
            } catch (_: SecurityException) { }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
    }
}

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MonitoringCoordinator(context).reconcile()
    }
}
