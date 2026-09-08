package com.example.onetapdnd

import android.content.Context
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

    fun reconcile() {
        val pauseExpired = store.clearExpiredPause()
        if (store.isPaused()) {
            store.clearPosition()
            PauseResumeWorker.schedule(
                context,
                (store.pauseUntilEpochMs() - System.currentTimeMillis()).coerceAtLeast(0L)
            )
        }
        val requestedMode = effectiveAudioMode()
        val dndReady = runCatching {
            DndController(context).sync()
            true
        }.getOrElse {
            store.status("Could not change DND. Check DND access, then retry.")
            false
        }
        val mode = requestedMode.takeIf { dndReady }
        PlaceAudioController(context).reconcile(mode)
        if (mode == PlaceAudioMode.DND_SILENT_MEDIA_ZERO) {
            runCatching { MediaMuteService.start(context) }.onFailure {
                store.status("Media mute could not stay active. Open the app to retry.")
            }
        } else {
            MediaMuteService.stop(context)
        }
        MonitoringNotification.update(context)
        if (pauseExpired) PlaceMonitoring.schedule(context)
    }

    fun effectiveAudioMode(): PlaceAudioMode? {
        if (store.isPaused() ||
            !DndController(context).hasAccess ||
            !PlaceMonitoring.hasPreciseLocation(context) ||
            !PlaceMonitoring.hasBackgroundLocation(context) ||
            !PlaceMonitoring.locationEnabled(context)
        ) return null
        return store.state().strongestMode(store.rules())
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
