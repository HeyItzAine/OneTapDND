package com.example.onetapdnd

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToLong

object AdaptiveLocationChecks {
    const val WORK_NAME = "adaptive-location-check"
    const val MIN_DELAY_MS = 5L * 60L * 1_000L
    const val MAX_DELAY_MS = 24L * 60L * 60L * 1_000L
    const val LOCATION_RETRY_DELAY_MS = 60L * 60L * 1_000L
    private const val BASELINE_SPEED_METERS_PER_SECOND = 1_000f / (5f * 60f)

    fun ensureScheduled(context: Context) {
        if (!shouldRun(context)) {
            cancel(context)
            PlaceStore(context).clearAdaptiveCheckStatus()
            return
        }
        enqueue(context, 0L, ExistingWorkPolicy.KEEP)
    }

    fun checkNow(context: Context) {
        if (!shouldRun(context)) {
            cancel(context)
            PlaceStore(context).clearAdaptiveCheckStatus()
            return
        }
        enqueue(context, 0L, ExistingWorkPolicy.REPLACE)
    }

    fun reschedule(context: Context, delayMs: Long) {
        if (!shouldRun(context)) {
            cancel(context)
            return
        }
        enqueue(context, delayMs, ExistingWorkPolicy.REPLACE)
    }

    fun scheduleNext(context: Context, delayMs: Long) {
        if (shouldRun(context)) {
            enqueue(context, delayMs, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun delayForDistance(distanceMeters: Float, speedMetersPerSecond: Float?): Long {
        val travelSpeed = max(
            speedMetersPerSecond?.takeIf { it.isFinite() && it > 0f }
                ?: BASELINE_SPEED_METERS_PER_SECOND,
            BASELINE_SPEED_METERS_PER_SECOND
        )
        val travelTimeMs = (distanceMeters.coerceAtLeast(0f) / travelSpeed * 1_000f).roundToLong()
        return travelTimeMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    private fun shouldRun(context: Context): Boolean {
        val store = PlaceStore(context)
        return !store.isPaused() &&
            store.rules().any { it.enabled } &&
            DndController(context).hasAccess &&
            PlaceMonitoring.hasPreciseLocation(context) &&
            PlaceMonitoring.hasBackgroundLocation(context) &&
            PlaceMonitoring.locationEnabled(context)
    }

    private fun enqueue(context: Context, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AdaptiveLocationWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, policy, request)
    }
}

class AdaptiveLocationWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {

    @SuppressLint("MissingPermission")
    override fun doWork(): Result {
        val context = applicationContext
        val store = PlaceStore(context)
        val rules = store.rules().filter { it.enabled }
        if (store.isPaused() || rules.isEmpty() ||
            !DndController(context).hasAccess ||
            !PlaceMonitoring.hasPreciseLocation(context) ||
            !PlaceMonitoring.hasBackgroundLocation(context) ||
            !PlaceMonitoring.locationEnabled(context)
        ) {
            store.clearAdaptiveCheckStatus()
            return Result.success()
        }

        val location = currentLocation(context)
        if (location == null) {
            val delay = AdaptiveLocationChecks.LOCATION_RETRY_DELAY_MS
            store.saveAdaptiveCheckStatus(
                "Location unavailable. Next distance check in 1 hour.",
                System.currentTimeMillis() + delay
            )
            MonitoringNotification.update(context)
            AdaptiveLocationChecks.scheduleNext(context, delay)
            return Result.success()
        }

        val previous = store.state()
        val distances = rules.associateWith { rule -> distanceTo(location, rule) }
        val accuracyBuffer = max(location.accuracy.takeIf { location.hasAccuracy() } ?: 0f, 25f)
        val inside = distances.filter { (rule, distance) ->
            if (rule.id in previous.inside) {
                distance <= rule.radiusMeters + accuracyBuffer
            } else {
                distance <= (rule.radiusMeters - accuracyBuffer).coerceAtLeast(0f)
            }
        }.keys.mapTo(mutableSetOf()) { it.id }
        if (inside != previous.inside) store.saveState(PlaceState(inside))
        MonitoringCoordinator(context).reconcile()

        val nearestDistance = distances.values.minOrNull() ?: 0f
        val speed = location.speed.takeIf { location.hasSpeed() }
        val delay = AdaptiveLocationChecks.delayForDistance(nearestDistance, speed)
        store.saveAdaptiveCheckStatus(
            "Nearest place ${formatDistance(nearestDistance)} away. Next distance check in ${formatDelay(delay)}.",
            System.currentTimeMillis() + delay
        )
        MonitoringNotification.update(context)
        AdaptiveLocationChecks.scheduleNext(context, delay)
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun currentLocation(context: Context): Location? {
        val token = CancellationTokenSource()
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(2L * 60L * 1_000L)
                .setDurationMillis(20_000L)
                .build()
            Tasks.await(
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, token.token),
                25L,
                TimeUnit.SECONDS
            )
        } catch (_: Exception) {
            null
        } finally {
            token.cancel()
        }
    }

    private fun distanceTo(location: Location, rule: PlaceRule): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            rule.latitude,
            rule.longitude,
            result
        )
        return result[0]
    }
}

internal fun formatDelay(delayMs: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delayMs).coerceAtLeast(1L)
    return when {
        minutes >= 24L * 60L -> "1 day"
        minutes == 60L -> "1 hour"
        minutes > 60L && minutes % 60L == 0L -> "${minutes / 60L} hours"
        minutes >= 60L -> "${minutes / 60L}h ${minutes % 60L}m"
        else -> "$minutes minutes"
    }
}

private fun formatDistance(distanceMeters: Float): String = if (distanceMeters < 1_000f) {
    "${distanceMeters.roundToLong()} m"
} else {
    String.format(Locale.US, "%.1f km", distanceMeters / 1_000f)
}
