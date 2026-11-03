package com.example.onetapdnd

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.Build
import android.service.notification.Condition
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class QuietTimeExpiryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = AutomationSettings(context)
    private val store = PlaceStore(context)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val dnd = DndController(context)

    @Before fun prepare() {
        check(context.packageName.endsWith(".debug"))
        shell("cmd notification allow_dnd ${context.packageName}")
        clean()
    }

    @After fun clean() {
        context.packageManager.setComponentEnabledSetting(ComponentName(context, AutomationReceiver::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        settings.preferences.edit().clear().commit()
        store.preferences.edit().clear().commit()
        context.stopService(Intent(context, MediaMuteService::class.java))
        WorkManager.getInstance(context).cancelAllWork().result.get()
        manager.automaticZenRules.keys.forEach(manager::removeAutomaticZenRule)
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        shell("cmd notification set_dnd all")
    }

    @Test fun dndOnlyPeriodEndsWithoutTheBoundaryBroadcast() {
        val now = stableStart()
        val start = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        settings.mode = AutomationMode.TIME
        settings.quietTimes = listOf(DailyQuietTime("expiry", DailyWindow(start, 1)))
        settings.enabled = true
        MonitoringCoordinator(context).reconcile()
        eventually(10_000) { dnd.isOn() }
        Thread.sleep(800)
        cancelBoundaryBroadcast()
        val end = now.apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, 1) }.timeInMillis
        eventually((end - System.currentTimeMillis()).coerceAtLeast(0) + 10_000) {
            System.currentTimeMillis() >= end && !dnd.isDeviceDndOn()
        }
        assertFalse(dnd.isOn())
        assertFalse(dnd.isManualDndRequested())
    }

    @Test fun mediaMutedPeriodReleasesDndAndRestoresAudio() {
        val now = stableStart()
        val audio = context.getSystemService(AudioManager::class.java)
        val originalVolume = minOf(5, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        settings.mode = AutomationMode.TIME
        settings.quietTimes = listOf(DailyQuietTime("media-expiry", DailyWindow(
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), 1)))
        settings.enabled = true
        settings.profilesEnabled = true
        settings.saveProfile(false, PlaceAudioMode.DND_SILENT_MEDIA_ZERO)
        settings.saveProfile(true, PlaceAudioMode.DND_SILENT_MEDIA_ZERO)
        MonitoringCoordinator(context).reconcile()
        eventually(10_000) { dnd.isOn() && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }
        Thread.sleep(800)
        cancelBoundaryBroadcast()
        val end = now.apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, 1) }.timeInMillis
        eventually((end - System.currentTimeMillis()).coerceAtLeast(0) + 10_000) {
            System.currentTimeMillis() >= end && !dnd.isDeviceDndOn() && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == originalVolume &&
                audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
        }
    }

    @Test fun releasingAutomaticPeriodPreservesManualDnd() {
        val now = Calendar.getInstance()
        settings.mode = AutomationMode.TIME
        settings.quietTimes = listOf(DailyQuietTime("manual-overlap", DailyWindow(
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), 10)))
        settings.enabled = true
        MonitoringCoordinator(context).reconcile()
        eventually(10_000) { dnd.isPlaceRuleActive() }
        dnd.setManualEnabled(true)
        settings.enabled = false
        // The service must release only the automatic rule without an app-screen refresh.
        eventually(10_000) {
            !dnd.isPlaceRuleActive() && dnd.isManualDndRequested() && dnd.isDeviceDndOn()
        }
        if (Build.VERSION.SDK_INT >= 35) {
            val automatic = manager.automaticZenRules.entries.single { it.value.conditionId.lastPathSegment == DndController.KEY_PLACES }
            assertEquals(Condition.STATE_FALSE, manager.getAutomaticZenRuleState(automatic.key))
        }
    }

    private fun stableStart(): Calendar {
        val now = Calendar.getInstance()
        if (now.get(Calendar.SECOND) >= 50) {
            Thread.sleep((60 - now.get(Calendar.SECOND)) * 1000L)
        }
        return Calendar.getInstance()
    }

    private fun cancelBoundaryBroadcast() {
        context.packageManager.setComponentEnabledSetting(ComponentName(context, AutomationReceiver::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        context.getSystemService(AlarmManager::class.java).cancel(PendingIntent.getBroadcast(context, 42,
            Intent(context, AutomationReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    private fun eventually(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        val audio = context.getSystemService(AudioManager::class.java)
        assertTrue("DND did not settle: filter=${manager.currentInterruptionFilter}, " +
            "ringer=${audio.ringerMode}, media=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}, " +
            "restore=${store.pendingRingerRestore()}", condition())
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
