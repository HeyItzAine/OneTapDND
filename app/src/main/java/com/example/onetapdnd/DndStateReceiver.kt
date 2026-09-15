package com.example.onetapdnd

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

class DndStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < 35 ||
            intent.action != NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED) return
        val status = intent.getIntExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS, -1)
        if (status != NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED &&
            status != NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED &&
            status != NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED) return
        val id = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID) ?: return
        val pending = goAsync()
        // Ringer changes can briefly snooze a rule before its recovery completes.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager.isNotificationPolicyAccessGranted) {
                    val rule = manager.getAutomaticZenRule(id)
                    if (rule?.conditionId?.lastPathSegment == DndController.KEY_PLACES) {
                        MonitoringCoordinator(context).reconcile()
                    }
                }
            } catch (_: SecurityException) {
                // Access may have been revoked while the broadcast was pending.
            } finally {
                pending.finish()
            }
        }, 350L)
    }
}
