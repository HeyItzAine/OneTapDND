package com.example.onetapdnd

import android.Manifest
import android.app.NotificationManager
import android.location.Location
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.Condition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceExitTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val store = PlaceStore(context)
    private val coordinator = MonitoringCoordinator(context)
    private val place = PlaceRule("exit-test", "Test place", 0.0, 0.0, 200f)

    @Before fun prepare() {
        shell("cmd notification allow_dnd ${context.packageName}")
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
        if (Build.VERSION.SDK_INT >= 29) {
            shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_BACKGROUND_LOCATION}")
        }
        shell("cmd location set-location-enabled true")
        clean()
    }

    @After fun clean() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        MediaMuteService.stop(context)
        store.preferences.edit().clear().commit()
        manager.automaticZenRules.keys.forEach { manager.removeAutomaticZenRule(it) }
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        eventually("DND cleared for test") {
            manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        }
    }

    @Test fun exitRestoresEachOriginalRingerAndTurnsOffPlaceDnd() {
        listOf(RingerMode.SOUND, RingerMode.VIBRATE, RingerMode.SILENT).forEach { original ->
            RingerMode.entries.forEach { target ->
                audio.ringerMode = original.platformValue
                val mode = when (target) {
                    RingerMode.SILENT -> PlaceAudioMode.DND_AND_SILENT
                    RingerMode.VIBRATE -> PlaceAudioMode.DND_AND_VIBRATE
                    RingerMode.SOUND -> PlaceAudioMode.DND_AND_SOUND
                }
                store.save(listOf(place.copy(audioMode = mode)))
                store.saveState(PlaceState(setOf(place.id)))
                coordinator.reconcile()
                eventually("place activated from ${original.label} with ${target.label}") { DndController(context).isOn() }
                assertEquals("original ${original.label} captured", original.platformValue, store.audioSnapshot().originalRingerMode)
                coordinator.reconcile()
                assertEquals("original survives another check", original.platformValue, store.audioSnapshot().originalRingerMode)
                store.clearPosition()
                coordinator.reconcile()
                eventually("${original.label} restored after exit") { audio.ringerMode == original.platformValue }
                assertFalse("place rule released", DndController(context).isPlaceDndRequested())
                if (original != RingerMode.SILENT) {
                    eventually("place DND turned off") {
                        manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                    }
                }
                clean()
            }
        }
    }

    @Test fun exitRestoresMediaAndPreservesManualDnd() {
        val originalVolume = minOf(5, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        DndController(context).toggle()
        eventually("manual DND on") { DndController(context).isOn() }
        store.save(listOf(place.copy(audioMode = PlaceAudioMode.DND_SILENT_MEDIA_ZERO)))
        store.saveState(PlaceState(setOf(place.id)))
        coordinator.reconcile()
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        store.clearPosition()
        coordinator.reconcile()
        eventually("media restored") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == originalVolume }
        assertTrue("manual DND remains on", DndController(context).isOn())
    }

    @Test fun freshLocationEndsDndAndAnOlderFixCannotReactivateIt() {
        store.save(listOf(place.copy(audioMode = PlaceAudioMode.DND_AND_SILENT)))
        val arrival = location(0.0)
        assertTrue(coordinator.onLocation(arrival))
        eventually("DND on inside radius") { DndController(context).isOn() }
        assertTrue(coordinator.onLocation(location(0.004)))
        assertTrue(store.state().inside.isEmpty())
        eventually("DND off outside radius") { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL }
        eventually("Sound restored") { audio.ringerMode == AudioManager.RINGER_MODE_NORMAL }
        assertFalse(coordinator.onLocation(arrival))
        coordinator.onGeofenceTransition(setOf(place.id), true, arrival.elapsedRealtimeNanos)
        assertTrue("delayed arrival cannot undo a newer exit", store.state().inside.isEmpty())
    }

    @Test fun geofenceExitRestoresAudioWithoutAForegroundLocationCheck() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        store.save(listOf(place.copy(audioMode = PlaceAudioMode.DND_AND_SILENT)))
        coordinator.onGeofenceTransition(setOf(place.id), true, SystemClock.elapsedRealtimeNanos())
        eventually("DND on after geofence arrival") { DndController(context).isOn() }
        coordinator.onGeofenceTransition(setOf(place.id), false, SystemClock.elapsedRealtimeNanos())
        eventually("DND off after geofence exit") { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL }
        eventually("Vibrate restored") { audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE }
    }

    @Test fun disablingTheActivePlaceRestoresItsSettings() {
        store.save(listOf(place.copy(audioMode = PlaceAudioMode.DND_AND_SILENT)))
        assertTrue(coordinator.onLocation(location(0.0)))
        eventually("DND on") { DndController(context).isOn() }
        store.save(listOf(place.copy(enabled = false)))
        coordinator.reconcile()
        eventually("DND off after disabling") { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL }
        eventually("Sound restored") { audio.ringerMode == AudioManager.RINGER_MODE_NORMAL }
    }

    @Test fun pauseRestoresSettingsAndIgnoresArrivalsUntilResumed() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        store.save(listOf(place.copy(audioMode = PlaceAudioMode.DND_AND_SILENT)))
        assertTrue(coordinator.onLocation(location(0.0)))
        eventually("DND on") { DndController(context).isOn() }
        coordinator.pauseFor(60)
        eventually("DND off while paused") { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL }
        eventually("Vibrate restored") { audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE }
        assertFalse(coordinator.onLocation(location(0.0)))
        coordinator.onGeofenceTransition(setOf(place.id), true, SystemClock.elapsedRealtimeNanos())
        assertTrue(store.state().inside.isEmpty())
    }

    @Test fun manualRingerChoiceInsideThePlaceSurvivesExit() {
        store.save(listOf(place.copy(audioMode = PlaceAudioMode.DND_AND_SILENT)))
        assertTrue(coordinator.onLocation(location(0.0)))
        eventually("DND on") { DndController(context).isOn() }
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertTrue(coordinator.onLocation(location(0.004)))
        eventually("DND off") { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL }
        eventually("manual Vibrate preserved") { audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE }
    }

    @Test fun allDuplicatePlaceRulesAreReleasedOnExit() {
        store.save(listOf(place))
        assertTrue(coordinator.onLocation(location(0.0)))
        val rule = manager.automaticZenRules.values.first { it.conditionId.lastPathSegment == DndController.KEY_PLACES }
        val duplicateId = manager.addAutomaticZenRule(rule)
        requireNotNull(duplicateId)
        manager.setAutomaticZenRuleState(duplicateId, Condition(rule.conditionId, "Test place", Condition.STATE_TRUE))
        eventually("duplicate DND active") { DndController(context).isOn() }
        assertTrue(coordinator.onLocation(location(0.004)))
        eventually("all place DND off") { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL }
    }

    private fun location(longitude: Double) = Location("test").apply {
        latitude = 0.0
        this.longitude = longitude
        accuracy = 5f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        time = System.currentTimeMillis()
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    private fun eventually(message: String, check: () -> Boolean) {
        repeat(60) {
            if (check()) return
            Thread.sleep(100)
        }
        assertTrue(message, check())
    }
}
