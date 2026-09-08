package com.example.onetapdnd

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

object MonitoringNotification {
    const val CHANNEL_ID = "quiet_places_status"
    const val STATUS_ID = 2001
    const val MEDIA_SERVICE_ID = 2002

    fun update(context: Context) {
        val appContext = context.applicationContext
        val store = PlaceStore(appContext)
        if (store.rules().none { it.enabled }) {
            NotificationManagerCompat.from(appContext).cancel(STATUS_ID)
            return
        }
        ensureChannel(appContext)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(appContext).notify(STATUS_ID, buildStatus(appContext))
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(STATUS_ID)
    }

    fun buildMediaService(context: Context): Notification {
        ensureChannel(context)
        return addPauseActions(baseBuilder(context), context)
            .setContentTitle("One Tap DND is holding media at zero")
            .setContentText("Leave the quiet place or pause monitoring to restore your previous volume.")
            .setOngoing(true)
            .build()
    }

    private fun buildStatus(context: Context): Notification {
        val store = PlaceStore(context)
        val pausedUntil = store.pauseUntilEpochMs()
        val paused = store.isPaused()
        val title = if (paused) "Quiet places paused" else "Quiet places active"
        val text = if (paused) {
            "Resumes ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pausedUntil))}"
        } else {
            store.adaptiveCheckStatus().takeIf { it.isNotBlank() } ?: store.status()
        }
        return addPauseActions(baseBuilder(context), context)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun addPauseActions(
        builder: NotificationCompat.Builder,
        context: Context
    ): NotificationCompat.Builder {
        val customMinutes = PlaceStore(context).customPauseMinutes()
        return builder
            .addAction(0, "Pause 1h", pauseIntent(context, PauseActionReceiver.ACTION_PAUSE_1_HOUR, 101))
            .addAction(0, "Pause 3h", pauseIntent(context, PauseActionReceiver.ACTION_PAUSE_3_HOURS, 103))
            .addAction(
                0,
                "Pause ${formatMinutes(customMinutes)}",
                pauseIntent(context, PauseActionReceiver.ACTION_PAUSE_CUSTOM, 104)
            )
    }

    private fun baseBuilder(context: Context): NotificationCompat.Builder {
        val openApp = PendingIntent.getActivity(
            context,
            100,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dnd)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
    }

    private fun pauseIntent(context: Context, actionSuffix: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PauseActionReceiver::class.java).setAction(context.packageName + actionSuffix),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quiet place monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Quiet place status and pause controls"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    internal fun formatMinutes(minutes: Int): String = when {
        minutes % 60 == 0 -> "${minutes / 60}h"
        minutes > 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
