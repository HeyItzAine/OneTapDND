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

    fun isOn(): Boolean {
        if (!hasAccess) return false
        if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return false
        val owned = manager.automaticZenRules
        return KEYS.any { key ->
            val entry = owned.entries.firstOrNull { it.value.conditionId == uri(key) }
            entry != null && entry.value.isEnabled &&
                if (Build.VERSION.SDK_INT >= 35) {
                    manager.getAutomaticZenRuleState(entry.key) == Condition.STATE_TRUE
                } else {
                    requested(key)
                }
        }
    }

    fun toggle() {
        check(hasAccess)
        preferences.edit().putBoolean(KEY_MANUAL, !preferences.getBoolean(KEY_MANUAL, false)).commit()
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

    fun sync(userAction: Boolean = false) {
        if (!hasAccess) return
        retireLegacySilenceRule()
        val owned = manager.automaticZenRules
        KEYS.forEach { key ->
            val entry = owned.entries.firstOrNull { it.value.conditionId == uri(key) }
            var id = entry?.key
            if (id == null && requested(key)) {
                @Suppress("DEPRECATION")
                val rule = AutomaticZenRule(
                    if (key == KEY_MANUAL) "One Tap DND" else "Quiet places",
                    ComponentName(context, DndConditionService::class.java),
                    uri(key),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    true
                )
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

    private fun retireLegacySilenceRule() {
        manager.automaticZenRules.entries
            .firstOrNull { it.value.conditionId == uri(KEY_LEGACY_SILENCE) }
            ?.let { manager.removeAutomaticZenRule(it.key) }
    }

    companion object {
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
