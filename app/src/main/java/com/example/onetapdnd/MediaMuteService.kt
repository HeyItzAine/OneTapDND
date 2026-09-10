package com.example.onetapdnd

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat

class MediaMuteService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var observerRegistered = false

    private val watchdog = object : Runnable {
        override fun run() {
            enforceFullSilence()
            if (runCatching { !audioManager.isVolumeFixed }.getOrDefault(false)) {
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }
    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            enforceFullSilence()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        val foregroundStarted = runCatching {
            startForeground(
                MonitoringNotification.MEDIA_SERVICE_ID,
                MonitoringNotification.buildMediaService(this)
            )
        }.isSuccess
        if (!foregroundStarted) {
            stopSelf()
            return
        }
        if (audioManager.isVolumeFixed) {
            PlaceStore(this).status("This device does not allow apps to change media volume.")
            MonitoringNotification.update(this)
            stopSelf()
            return
        }
        observerRegistered = runCatching {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
            true
        }.getOrDefault(false)
        handler.post(watchdog)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!runCatching { shouldEnforce() }.getOrDefault(false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        enforceFullSilence()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (observerRegistered) runCatching { contentResolver.unregisterContentObserver(volumeObserver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shouldEnforce(): Boolean {
        return MonitoringCoordinator(this).effectiveAudioMode() ==
            PlaceAudioMode.DND_SILENT_MEDIA_ZERO
    }

    private fun enforceFullSilence() {
        if (!runCatching { shouldEnforce() }.getOrDefault(false)) {
            stopSelf()
            return
        }
        val audioController = PlaceAudioController(this)
        audioController.enforceRingerSilence()
        audioController.enforceMediaZero()
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, MediaMuteService::class.java)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, MediaMuteService::class.java)
            )
        }
    }
}
