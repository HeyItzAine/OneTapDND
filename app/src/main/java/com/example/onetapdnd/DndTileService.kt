package com.example.onetapdnd

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DndTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        val notificationManager = getSystemService(NotificationManager::class.java)

        if (!notificationManager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this, 0, intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        val currentFilter = notificationManager.currentInterruptionFilter
        if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (!notificationManager.isNotificationPolicyAccessGranted) {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = getString(R.string.tile_tap_to_setup)
            tile.updateTile()
            return
        }

        val dndOn = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        tile.state = if (dndOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (dndOn) getString(R.string.tile_on) else getString(R.string.tile_off)
        tile.updateTile()
    }
}
