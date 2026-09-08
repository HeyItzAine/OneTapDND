package com.example.onetapdnd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PauseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = PlaceStore(context)
        val minutes = when (intent.action) {
            context.packageName + ACTION_PAUSE_1_HOUR -> 60
            context.packageName + ACTION_PAUSE_3_HOURS -> 180
            context.packageName + ACTION_PAUSE_CUSTOM -> store.customPauseMinutes()
            else -> return
        }
        MonitoringCoordinator(context).pauseFor(minutes)
    }

    companion object {
        const val ACTION_PAUSE_1_HOUR = ".action.PAUSE_1_HOUR"
        const val ACTION_PAUSE_3_HOURS = ".action.PAUSE_3_HOURS"
        const val ACTION_PAUSE_CUSTOM = ".action.PAUSE_CUSTOM"
    }
}
