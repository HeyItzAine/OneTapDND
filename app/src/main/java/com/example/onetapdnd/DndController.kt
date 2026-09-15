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
    val hasAccess: Boolean
        get() = manager.isNotificationPolicyAccessGranted

    private fun uri(key: String) = Uri.parse("condition://${context.packageName}/$key")

    private fun requested(key: String): Boolean = when (key) {
        KEY_MANUAL -> preferences.getBoolean(KEY_MANUAL, false)
        KEY_PLACES -> !store.isPaused() &&
            PlaceMonitoring.hasPreciseLocation(context) &&
            PlaceMonitoring.hasBackgroundLocation(context) &&
            PlaceMonitoring.locationEnabled(context) &&
            store.state().active(store.rules()).isNotEmpty()
        else -> false
    }

    fun isPlaceDndRequested(): Boolean = requested(KEY_PLACES)

    fun isManualDndRequested(): Boolean = requested(KEY_MANUAL)

    fun isDeviceDndOn(): Boolean = when (manager.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        NotificationManager.INTERRUPTION_FILTER_ALARMS,
        NotificationManager.INTERRUPTION_FILTER_NONE -> true
        else -> false
    }

    fun isPlaceRuleActive(): Boolean = hasAccess &&
        manager.automaticZenRules.any { (id, rule) ->
            rule.conditionId == uri(KEY_PLACES) && rule.isEnabled &&
                if (Build.VERSION.SDK_INT >= 35) manager.getAutomaticZenRuleState(id) == Condition.STATE_TRUE
                else requested(KEY_PLACES) && isDeviceDndOn()
        }

    fun activeRuleIds(): Set<String> = if (!hasAccess) emptySet() else
        manager.automaticZenRules.filter { (id, rule) ->
            KEYS.any { rule.conditionId == uri(it) } && rule.isEnabled &&
                if (Build.VERSION.SDK_INT >= 35) manager.getAutomaticZenRuleState(id) == Condition.STATE_TRUE
                else requested(rule.conditionId.lastPathSegment.orEmpty())
        }.keys

    fun recoverAfterRingerChange(previouslyActive: Set<String>) = synchronized(ruleLock) {
        if (!hasAccess || Build.VERSION.SDK_INT < 29) return@synchronized
        // Android can snooze DND when our ringer write selects Sound or Vibrate.
        // Recover only our previously active rules that are still requested.
        manager.automaticZenRules.filterKeys { it in previouslyActive }.forEach { (id, rule) ->
            if (requested(rule.conditionId.lastPathSegment.orEmpty())) {
                if (Build.VERSION.SDK_INT >= 35) {
                    manager.setAutomaticZenRuleState(id, condition(rule.conditionId, userAction = true))
                    manager.setAutomaticZenRuleState(id, condition(rule.conditionId))
                } else {
                    manager.setAutomaticZenRuleState(id, Condition(rule.conditionId, "One Tap DND", Condition.STATE_FALSE))
                    manager.setAutomaticZenRuleState(id, condition(rule.conditionId))
                }
            }
        }
    }

    fun isOn(): Boolean {
        if (!hasAccess) return false
        if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return false
        val owned = manager.automaticZenRules
        return KEYS.any { key ->
            owned.entries.any { entry ->
                entry.value.conditionId == uri(key) && entry.value.isEnabled &&
                if (Build.VERSION.SDK_INT >= 35) {
                    manager.getAutomaticZenRuleState(entry.key) == Condition.STATE_TRUE
                } else {
                    requested(key)
                }
            }
        }
    }

    // False means Android settings must handle DND owned by the system or another app.
    fun toggle(): Boolean {
        check(hasAccess)
        if (isDeviceDndOn()) {
            if (!isOn()) return false
            preferences.edit().putBoolean(KEY_MANUAL, false).commit()
            if (isPlaceDndRequested()) MonitoringCoordinator(context).pauseFor(60)
            sync(userAction = true)
        } else {
            setManualEnabled(true)
        }
        MonitoringCoordinator(context).reconcile()
        return true
    }

    fun setManualEnabled(enabled: Boolean) {
        check(hasAccess)
        preferences.edit().putBoolean(KEY_MANUAL, enabled).commit()
        sync(userAction = true)
    }

    fun condition(id: Uri, userAction: Boolean = false): Condition {
        val state = if (requested(id.lastPathSegment.orEmpty())) {
            Condition.STATE_TRUE
        } else {
            Condition.STATE_FALSE
        }
        return if (Build.VERSION.SDK_INT >= 35) {
            Condition(
                id,
                "One Tap DND",
                state,
                if (userAction) Condition.SOURCE_USER_ACTION else Condition.SOURCE_CONTEXT
            )
        } else {
            Condition(id, "One Tap DND", state)
        }
    }

    fun sync(userAction: Boolean = false): Unit = synchronized(ruleLock) {
        if (!hasAccess) return
        retireLegacySilenceRule()
        val owned = manager.automaticZenRules
        KEYS.forEach { key ->
            val entries = owned.entries.filter { it.value.conditionId == uri(key) }
            val ids = entries.mapTo(mutableListOf()) { it.key }
            if (ids.isEmpty() && requested(key)) {
                @Suppress("DEPRECATION")
                val rule = AutomaticZenRule(
                    if (key == KEY_MANUAL) "One Tap DND" else "Quiet places",
                    ComponentName(context, DndConditionService::class.java),
                    uri(key),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    true
                )
                manager.addAutomaticZenRule(rule)?.let(ids::add)
            } else if (userAction && requested(key)) {
                entries.filter { !it.value.isEnabled }.forEach { entry ->
                    entry.value.isEnabled = true
                    manager.updateAutomaticZenRule(entry.key, entry.value)
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ids.forEach { id ->
                    manager.setAutomaticZenRuleState(id, condition(uri(key), userAction))
                    if (userAction && Build.VERSION.SDK_INT >= 35) {
                        // Acknowledge the matching automatic state so the next place exit can release it.
                        manager.setAutomaticZenRuleState(id, condition(uri(key)))
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT < 29) DndConditionService.publish(context)
    }

    private fun retireLegacySilenceRule() {
        manager.automaticZenRules.entries
            .filter { it.value.conditionId == uri(KEY_LEGACY_SILENCE) }
            .forEach { manager.removeAutomaticZenRule(it.key) }
    }

    companion object {
        private val ruleLock = Any()
        const val KEY_MANUAL = "manual"
        const val KEY_PLACES = "places"
        private const val KEY_LEGACY_SILENCE = "silence"
        val KEYS = listOf(KEY_MANUAL, KEY_PLACES)
    }
}

class DndConditionService : ConditionProviderService() {
    override fun onConnected() {
        instance = this
        publish(this)
    }

    override fun onSubscribe(conditionId: Uri) {
        notifyCondition(DndController(this).condition(conditionId))
    }

    override fun onUnsubscribe(conditionId: Uri) = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: DndConditionService? = null

        fun publish(context: Context) {
            val service = instance
            if (service == null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    requestRebind(ComponentName(context, DndConditionService::class.java))
                }
            } else {
                DndController.KEYS.forEach { key ->
                    service.notifyCondition(
                        DndController(context).condition(
                            Uri.parse("condition://${context.packageName}/$key")
                        )
                    )
                }
            }
        }
    }
}
