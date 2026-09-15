package com.example.onetapdnd

import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class MonitoringCoordinator(context: Context) {
    private val context = context.applicationContext
    private val store = PlaceStore(this.context)

    fun onPlacesChanged() {
        reconcile()
        PlaceMonitoring.schedule(context)
        AdaptiveLocationChecks.checkNow(context)
    }

    fun pauseFor(minutes: Int) {
        require(PlaceStore.isValidPauseMinutes(minutes))
        val durationMs = TimeUnit.MINUTES.toMillis(minutes.toLong())
        store.savePauseUntilEpochMs(System.currentTimeMillis() + durationMs)
        store.clearPosition()
        PauseResumeWorker.schedule(context, durationMs)
        reconcile()
        PlaceMonitoring.schedule(context)
    }

    fun resumeNow() {
        store.savePauseUntilEpochMs(0L)
        store.clearPosition()
        WorkManager.getInstance(context).cancelUniqueWork(PauseResumeWorker.WORK_NAME)
        reconcile()
        PlaceMonitoring.schedule(context)
        AdaptiveLocationChecks.checkNow(context)
    }

    fun onLocation(location: Location): Boolean = synchronized(stateLock) {
        if (store.isPaused() || !canMonitorPlaces() || !location.hasAccuracy() ||
            !location.accuracy.isFinite() || location.accuracy < 0f ||
            !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
            !acceptsLocationSample(location.elapsedRealtimeNanos, store.lastLocationObservationNanos(), SystemClock.elapsedRealtimeNanos())
        ) return@synchronized false
        val previous = store.state()
        val inside = store.rules().filter { it.enabled }.filter { rule ->
            val distance = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, rule.latitude, rule.longitude, distance)
            insideAfterLocation(rule.id in previous.inside, distance[0], rule.radiusMeters, location.accuracy)
        }.mapTo(mutableSetOf()) { it.id }
        store.saveLocationState(PlaceState(inside), location.elapsedRealtimeNanos)
        store.status(if (inside.isEmpty()) "Location checked: outside all enabled places."
            else "Location checked: inside ${inside.size} enabled ${if (inside.size == 1) "place" else "places"}.")
        reconcile()
        MonitoringNotification.update(context)
        true
    }

    fun onGeofenceTransition(ids: Set<String>, entering: Boolean, observationNanos: Long) = synchronized(stateLock) {
        if (store.isPaused() || !canMonitorPlaces()) return@synchronized
        val now = SystemClock.elapsedRealtimeNanos()
        val observed = observationNanos.takeIf { it in 1L..now } ?: now
        val previousTime = store.lastLocationObservationNanos().takeIf { it in 1L..now } ?: 0L
        if (observed < previousTime) return@synchronized
        val enabledIds = store.rules().filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
        val validIds = ids intersect enabledIds
        if (validIds.isEmpty()) return@synchronized
        store.saveLocationState(store.state().transition(validIds, entering), observed)
        reconcile()
    }

    fun reconcile(): Unit = synchronized(stateLock) {
        val pauseExpired = store.clearExpiredPause()
        if (store.isPaused()) {
            store.clearPosition()
            PauseResumeWorker.schedule(
                context,
                (store.pauseUntilEpochMs() - System.currentTimeMillis()).coerceAtLeast(0L)
            )
        }
        val requestedMode = requestedAudioMode()
        val audioController = PlaceAudioController(context)
        val dnd = DndController(context)
        val rulesBeforeRestore = dnd.activeRuleIds()
        if (requestedMode?.mutesMedia != true) MediaMuteService.stop(context)
        audioController.prepare(requestedMode)
        val dndReady = runCatching {
            dnd.sync()
            true
        }.getOrElse {
            store.status("Could not change DND. Check DND access, then retry.")
            false
        }
        val mode = requestedMode.takeIf { dndReady && dnd.isPlaceRuleActive() }
        if (mode != requestedMode) audioController.prepare(mode)
        if (dndReady) {
            val activeRules = dnd.activeRuleIds() + rulesBeforeRestore
            val deferRestore = mode?.ringer == null && store.pendingRingerRestore() != null
            if (audioController.apply(mode, restoreRinger = !deferRestore)) {
                dnd.recoverAfterRingerChange(activeRules)
                audioController.recordAppliedRinger()
            }
            if (deferRestore) AudioRestoreWorker.schedule(context)
        } else {
            audioController.prepare(null)
            audioController.apply(null)
        }
        if (mode?.mutesMedia == true) {
            runCatching { MediaMuteService.start(context) }.onFailure {
                store.status("Media mute could not stay active. Open the app to retry.")
            }
        } else {
            MediaMuteService.stop(context)
        }
        MonitoringNotification.update(context)
        if (pauseExpired) PlaceMonitoring.schedule(context)
    }

    fun finishAudioRestoration(): Boolean = synchronized(stateLock) {
        val mode = effectiveAudioMode()
        if (mode?.ringer != null || store.pendingRingerRestore() == null) return@synchronized true
        val dnd = DndController(context)
        val activeRules = dnd.activeRuleIds()
        val audio = PlaceAudioController(context)
        audio.prepare(mode)
        if (audio.apply(mode)) dnd.recoverAfterRingerChange(activeRules)
        MonitoringNotification.update(context)
        store.pendingRingerRestore() == null
    }

    fun effectiveAudioMode(): PlaceAudioMode? {
        if (!DndController(context).isPlaceRuleActive()) return null
        return requestedAudioMode()
    }

    private fun requestedAudioMode(): PlaceAudioMode? {
        if (store.isPaused() || !canMonitorPlaces()) return null
        return store.state().strongestMode(store.rules())
    }

    private fun canMonitorPlaces(): Boolean =
        DndController(context).hasAccess &&
            PlaceMonitoring.hasPreciseLocation(context) &&
            PlaceMonitoring.hasBackgroundLocation(context) &&
            PlaceMonitoring.locationEnabled(context)

    companion object {
        private val stateLock = Any()
    }
}

class AudioRestoreWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result =
        if (MonitoringCoordinator(applicationContext).finishAudioRestoration()) Result.success() else Result.retry()

    companion object {
        fun schedule(context: Context) {
            // AudioService applies DND changes asynchronously. Persist the restore
            // so it runs after that transition and survives a process restart.
            val request = OneTimeWorkRequestBuilder<AudioRestoreWorker>()
                .setInitialDelay(300, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("restore-place-audio", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

class PauseResumeWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val store = PlaceStore(applicationContext)
        val remaining = store.pauseUntilEpochMs() - System.currentTimeMillis()
        if (remaining > 0L) {
            schedule(applicationContext, remaining)
            return Result.success()
        }
        MonitoringCoordinator(applicationContext).reconcile()
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "resume-place-monitoring"

        fun schedule(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<PauseResumeWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
