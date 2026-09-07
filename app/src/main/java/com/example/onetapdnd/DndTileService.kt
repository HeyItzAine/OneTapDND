package com.example.onetapdnd

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class DndTileService : TileService() {
    private var listening = false
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateTileState() }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!listening) {
            val filter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED).apply {
                addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                if (Build.VERSION.SDK_INT >= 30) addAction(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED)
            }
            ContextCompat.registerReceiver(this, changes, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            listening = true
        }
        updateTileState()
    }

    override fun onStopListening() {
        if (listening) { unregisterReceiver(changes); listening = false }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        try {
            val controller = DndController(this)
            if (!controller.hasAccess) { openSettings(); return }
            controller.toggle()
            updateTileState()
        } catch (_: SecurityException) {
            openSettings()
        } catch (_: IllegalArgumentException) {
            openApp()
        } catch (_: IllegalStateException) {
            openApp()
        }
    }

    private fun openSettings() = launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    private fun openApp() = launch(Intent(this, MainActivity::class.java))

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val controller = DndController(this)
        val hasAccess = controller.hasAccess
        val on = runCatching { controller.isOn() }.getOrDefault(false)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                !hasAccess -> getString(R.string.tile_tap_to_setup)
                on -> getString(R.string.tile_on)
                manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL -> "Other mode active"
                else -> getString(R.string.tile_off)
            }
        }
        tile.updateTile()
    }
}
