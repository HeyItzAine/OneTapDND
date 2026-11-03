package com.example.onetapdnd

import android.app.Service
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat

class MediaMuteService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var observerRegistered = false
    private var restoringAudio = false
    private val audioRestore = object : Runnable {
        override fun run() {
            if (runCatching { shouldEnforce() }.getOrDefault(false)) {
                restoringAudio = false
                return
            }
            val restored = runCatching { MonitoringCoordinator(this@MediaMuteService).finishAudioRestoration() }
                .getOrDefault(false)
            if (restored) {
                restoringAudio = false
                if (!runCatching { shouldEnforce() }.getOrDefault(false)) stopSelf()
            } else {
                handler.postDelayed(this, 350L)
            }
        }
    }
    private val boundaryAlarm = AlarmManager.OnAlarmListener {
        MonitoringCoordinator(this).reconcile()
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { MonitoringCoordinator(this@MediaMuteService).reconcile() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { MonitoringCoordinator(this@MediaMuteService).reconcile() }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            enforceMediaMute()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }
    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            enforceMediaMute()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
        if (audioManager.isVolumeFixed && AutomationSettings(this).requestedAudioMode()?.mutesMedia == true) {
            PlaceStore(this).status("This device does not allow apps to change media volume.")
            MonitoringNotification.update(this)
        }
        observerRegistered = runCatching {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
            true
        }.getOrDefault(false)
        audioManager.registerAudioDeviceCallback(devices, handler)
        handler.post(watchdog)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(MonitoringNotification.MEDIA_SERVICE_ID, MonitoringNotification.buildMediaService(this))
        scheduleBoundaryCheck()
        enforceMediaMute()
        return if (runCatching { shouldEnforce() }.getOrDefault(false)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        getSystemService(AlarmManager::class.java).cancel(boundaryAlarm)
        audioManager.unregisterAudioDeviceCallback(devices)
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(audioRestore)
        if (observerRegistered) runCatching { contentResolver.unregisterContentObserver(volumeObserver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shouldEnforce(): Boolean {
        return MonitoringCoordinator(this).effectiveAudioMode() != null
    }

    private fun scheduleBoundaryCheck() {
        val alarms = getSystemService(AlarmManager::class.java)
        alarms.cancel(boundaryAlarm)
        val settings = AutomationSettings(this)
        if (!settings.mode.usesTime || !settings.enabled) return
        val next = DailySchedule(settings.quietTimes).nextBoundary(System.currentTimeMillis()) ?: return
        // The listener variant needs no exact-alarm permission and lives with this service.
        // Keep the persisted broadcast alarm as the fallback after process death.
        runCatching { alarms.setExact(AlarmManager.RTC_WAKEUP, next, "quiet-time-boundary", boundaryAlarm, handler) }
    }

    private fun enforceMediaMute() {
        if (!runCatching { shouldEnforce() }.getOrDefault(false)) {
            // Expiry must release the rule and restore audio before the monitor exits.
            runCatching { MonitoringCoordinator(this).reconcile() }
            stopAfterAudioRestore()
            return
        }
        val audioController = PlaceAudioController(this)
        if (MonitoringCoordinator(this).effectiveAudioMode()?.mutesMedia == true) audioController.enforceMediaZero()
    }

    private fun stopAfterAudioRestore() {
        if (runCatching { shouldEnforce() }.getOrDefault(false)) return
        if (PlaceStore(this).pendingRingerRestore() == null) {
            stopSelf()
        } else if (!restoringAudio) {
            restoringAudio = true
            // Allow AudioService to finish releasing DND before restoring the ringer.
            handler.postDelayed(audioRestore, 350L)
        }
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 1_000L
        private var instance: MediaMuteService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, MediaMuteService::class.java)
            )
        }

        fun stop(context: Context) {
            // Let a pending foreground start complete before the service stops itself.
            mainHandler.post { instance?.stopAfterAudioRestore() }
        }
    }
}
