package com.example.onetapdnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ConditionProviderService

class DndController(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val store = PlaceStore(context)
    private val preferences = store.preferences
    val hasAccess: Boolean get() = manager.isNotificationPolicyAccessGranted
    private fun uri(key: String) = Uri.parse("condition://${context.packageName}/$key")
    private val keys = listOf("manual", "places", "silence")

    private fun requested(key: String): Boolean {
        val active = store.state().active(store.rules())
        return when (key) {
            "manual" -> preferences.getBoolean("manual", false)
            "places" -> active.any { !it.totalSilence }
            "silence" -> active.any { it.totalSilence }
            else -> false
        }
    }

    fun isOn(): Boolean {
        if (!hasAccess) return false
        if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return false
        val owned = manager.automaticZenRules
        if (Build.VERSION.SDK_INT >= 35 && !preferences.getBoolean("explicit_rules", false) &&
            owned.any { (id, rule) -> rule.conditionId !in keys.map { uri(it) } &&
                rule.isEnabled && manager.getAutomaticZenRuleState(id) == Condition.STATE_TRUE }) return true
        return keys.any { key ->
            val entry = owned.entries.firstOrNull { it.value.conditionId == uri(key) }
            entry != null && entry.value.isEnabled &&
                if (Build.VERSION.SDK_INT >= 35) {
                    manager.getAutomaticZenRuleState(entry.key) == Condition.STATE_TRUE
                } else requested(key) &&
                    manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        }
    }

    fun toggle() {
        check(hasAccess)
        val turnOn = !isOn()
        preferences.edit().putBoolean("manual", turnOn).commit()
        if (!turnOn) {
            val state = store.state()
            store.saveState(state.copy(paused = state.paused + state.inside))
        }
        sync(userAction = true)
    }

    fun condition(id: Uri, userAction: Boolean = false): Condition {
        val state = if (requested(id.lastPathSegment.orEmpty())) Condition.STATE_TRUE else Condition.STATE_FALSE
        return if (Build.VERSION.SDK_INT >= 35) {
            Condition(id, "One Tap DND", state,
                if (userAction) Condition.SOURCE_USER_ACTION else Condition.SOURCE_CONTEXT)
        } else Condition(id, "One Tap DND", state)
    }

    fun sync(userAction: Boolean = false) {
        if (!hasAccess) return
        // Retire the implicit rule used by v1.0 without touching other apps' rules.
        if (Build.VERSION.SDK_INT >= 35 && !preferences.getBoolean("explicit_rules", false)) {
            if (manager.automaticZenRules.values.any { it.conditionId !in keys.map { key -> uri(key) } }) {
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            preferences.edit().putBoolean("explicit_rules", true).commit()
        }
        val owned = manager.automaticZenRules
        keys.forEach { key ->
            val entry = owned.entries.firstOrNull { it.value.conditionId == uri(key) }
            var id = entry?.key
            if (id == null && requested(key)) {
                @Suppress("DEPRECATION")
                val rule = AutomaticZenRule(
                    when (key) { "manual" -> "One Tap DND"; "places" -> "Quiet places"; else -> "Quiet places: total silence" },
                    ComponentName(context, DndConditionService::class.java), uri(key),
                    if (key == "silence") NotificationManager.INTERRUPTION_FILTER_NONE
                    else NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
                id = manager.addAutomaticZenRule(rule)
            } else if (entry != null && userAction && requested(key) && !entry.value.isEnabled) {
                entry.value.isEnabled = true
                manager.updateAutomaticZenRule(entry.key, entry.value)
            }
            if (id != null && Build.VERSION.SDK_INT >= 29) {
                manager.setAutomaticZenRuleState(id, condition(uri(key), userAction))
            }
        }
        if (Build.VERSION.SDK_INT < 29) DndConditionService.publish(context)
    }
}

class DndConditionService : ConditionProviderService() {
    override fun onConnected() { instance = this; publish(this) }
    override fun onSubscribe(conditionId: Uri) {
        notifyCondition(DndController(this).condition(conditionId))
    }
    override fun onUnsubscribe(conditionId: Uri) = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        private var instance: DndConditionService? = null
        fun publish(context: Context) {
            val service = instance
            if (service == null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    requestRebind(ComponentName(context, DndConditionService::class.java))
                }
            } else {
                listOf("manual", "places", "silence").forEach {
                    service.notifyCondition(DndController(context).condition(
                        Uri.parse("condition://${context.packageName}/$it")))
                }
            }
        }
    }
}
