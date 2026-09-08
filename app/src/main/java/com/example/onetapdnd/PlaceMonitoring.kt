package com.example.onetapdnd

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

object PlaceMonitoring {
    fun hasPreciseLocation(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    fun locationEnabled(context: Context): Boolean = LocationManagerCompat.isLocationEnabled(
        context.getSystemService(LocationManager::class.java))

    fun schedule(context: Context) {
        // Serialize registration so an older request cannot overwrite a later edit.
        WorkManager.getInstance(context).enqueueUniqueWork("register-places",
            ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<PlaceRegistrationWorker>().build())
        AdaptiveLocationChecks.ensureScheduled(context)
    }

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 10, Intent(context, GeofenceReceiver::class.java).setAction("${context.packageName}.GEOFENCE"),
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)

    fun errorMessage(error: Throwable): String {
        val cause = error.cause ?: error
        return when ((cause as? ApiException)?.statusCode) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> "Location monitoring unavailable. Turn on Location and Google Location Accuracy, then retry."
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> "Android's place limit was reached. Remove a saved place and retry."
            else -> "Could not start place monitoring. Check location access and Google Play services, then retry."
        }
    }
}

class PlaceRegistrationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    @SuppressLint("MissingPermission")
    override fun doWork(): Result {
        val context = applicationContext
        val store = PlaceStore(context)
        val client = LocationServices.getGeofencingClient(context)
        val rules = store.rules().filter { it.enabled }
        val problem = when {
            store.isPaused() -> "Quiet places paused."
            rules.isEmpty() -> "No places enabled."
            !DndController(context).hasAccess -> "Grant DND access to enable quiet places."
            !PlaceMonitoring.hasPreciseLocation(context) -> "Allow precise location to enable quiet places."
            !PlaceMonitoring.hasBackgroundLocation(context) -> "Set location access to Allow all the time."
            !PlaceMonitoring.locationEnabled(context) -> "Turn on device Location, then retry."
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS ->
                "Quiet places need Google Play services. Install or update it, then retry."
            else -> null
        }
        if (problem != null) {
            runCatching { Tasks.await(client.removeGeofences(PlaceMonitoring.pendingIntent(context)), 20, TimeUnit.SECONDS) }
            store.clearPosition()
            MonitoringCoordinator(context).reconcile()
            store.status(problem)
            MonitoringNotification.update(context)
            return Result.success()
        }
        return try {
            Tasks.await(client.removeGeofences(PlaceMonitoring.pendingIntent(context)), 20, TimeUnit.SECONDS)
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
                .addGeofences(rules.map { rule ->
                    Geofence.Builder().setRequestId(rule.id)
                        .setCircularRegion(rule.latitude, rule.longitude, rule.radiusMeters)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                        .setNotificationResponsiveness(60_000).build()
                }).build()
            Tasks.await(client.addGeofences(request, PlaceMonitoring.pendingIntent(context)), 20, TimeUnit.SECONDS)
            store.status("Monitoring ${rules.size} saved ${if (rules.size == 1) "place" else "places"}.")
            MonitoringNotification.update(context)
            Result.success()
        } catch (error: Exception) {
            store.clearPosition()
            MonitoringCoordinator(context).reconcile()
            store.status(PlaceMonitoring.errorMessage(error))
            MonitoringNotification.update(context)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        val store = PlaceStore(context)
        if (store.isPaused()) {
            PlaceMonitoring.schedule(context)
            return
        }
        if (event.hasError()) {
            store.clearPosition()
            MonitoringCoordinator(context).reconcile()
            store.status("Location monitoring interrupted. Open the app to retry.")
            MonitoringNotification.update(context)
            PlaceMonitoring.schedule(context)
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val enabled = store.rules().filter { it.enabled }.map { it.id }.toSet()
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }.toSet() intersect enabled
        if (ids.isEmpty()) return
        if (!PlaceMonitoring.hasPreciseLocation(context) || !PlaceMonitoring.hasBackgroundLocation(context)) {
            store.clearPosition()
            MonitoringCoordinator(context).reconcile()
            store.status("Location access changed. Open the app to restore monitoring.")
            MonitoringNotification.update(context)
            PlaceMonitoring.schedule(context)
            return
        }
        store.saveState(store.state().transition(ids, event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER))
        runCatching { MonitoringCoordinator(context).reconcile() }.onFailure {
            store.status("Could not change DND. Check DND access, then retry.")
            MonitoringNotification.update(context)
        }
        AdaptiveLocationChecks.reschedule(context, AdaptiveLocationChecks.MIN_DELAY_MS)
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Recheck position after a reboot before restoring place-based silence.
            PlaceStore(context).clearPosition()
        }
        MonitoringCoordinator(context).reconcile()
        PlaceMonitoring.schedule(context)
    }
}
