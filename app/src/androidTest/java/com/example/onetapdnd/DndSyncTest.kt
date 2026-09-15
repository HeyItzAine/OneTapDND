package com.example.onetapdnd

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class DndSyncTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val store = PlaceStore(context)
    private val coordinator = MonitoringCoordinator(context)
    private val dnd = DndController(context)
    private val place = PlaceRule("sync-test", "Test place", 0.0, 0.0, 200f)

    @Before fun prepare() {
        check(context.packageName.endsWith(".debug"))
        shell("cmd notification allow_dnd ${context.packageName}")
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION).forEach { shell("pm grant ${context.packageName} $it") }
        shell("cmd location set-location-enabled true")
        clean()
    }

    @After fun clean() {
        store.preferences.edit().clear().commit()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        Thread.sleep(600)
        MediaMuteService.stop(context)
        store.preferences.edit().clear().commit()
        manager.automaticZenRules.keys.forEach(manager::removeAutomaticZenRule)
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        shell("cmd notification set_dnd all")
        eventually { !dnd.isDeviceDndOn() }
    }

    @Test fun repeatedPlaceChecksDoNotFlickerDnd() {
        listOf(PlaceAudioMode.DND_AND_SOUND, PlaceAudioMode.DND_AND_VIBRATE,
            PlaceAudioMode.DND_AND_SILENT).forEach { mode ->
            store.save(listOf(place.copy(audioMode = mode)))
            store.saveState(PlaceState(setOf(place.id)))
            coordinator.reconcile()
            eventually { dnd.isOn() && store.audioSnapshot().requestedRingerMode == mode.ringer!!.platformValue }
            Thread.sleep(700)
            val changes = AtomicInteger()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { changes.incrementAndGet() }
            }
            ContextCompat.registerReceiver(context, receiver,
                IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                repeat(25) {
                    coordinator.reconcile()
                    Thread.sleep(60)
                    assertTrue("DND stayed on for $mode", dnd.isDeviceDndOn())
                }
                assertEquals("No DND transitions during repeated $mode checks", 0, changes.get())
            } finally { context.unregisterReceiver(receiver) }
        }
    }

    @Test fun systemOffIsRespectedInsidePlaceAndNextTapTurnsOn() {
        store.save(listOf(place.copy(audioMode = PlaceAudioMode.DND_AND_VIBRATE)))
        store.saveState(PlaceState(setOf(place.id)))
        coordinator.reconcile()
        eventually { dnd.isOn() }
        val id = manager.automaticZenRules.entries.single { it.value.conditionId.lastPathSegment == "places" }.key
        shell("am start -a android.settings.AUTOMATIC_ZEN_RULE_SETTINGS --es android.provider.extra.AUTOMATIC_ZEN_RULE_ID $id")
        eventually {
            val node = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("Turn off")?.firstOrNull()
            var button = node
            while (button != null && !button.isClickable) button = button.parent
            button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        eventually { !dnd.isDeviceDndOn() }
        repeat(10) { coordinator.reconcile(); Thread.sleep(100) }
        assertFalse("A snoozed place must stay off; inside=${store.state().inside}; rules=${manager.automaticZenRules}; audio=${store.audioSnapshot()}", dnd.isDeviceDndOn())
        assertNull("Place audio follows the system override", coordinator.effectiveAudioMode())
        assertTrue(dnd.toggle())
        eventually { dnd.isDeviceDndOn() }
        shell("input keyevent KEYCODE_HOME")
    }

    @Test fun toggleOffReleasesBothManualAndPlaceRules() {
        store.save(listOf(place))
        store.saveState(PlaceState(setOf(place.id)))
        coordinator.reconcile()
        dnd.setManualEnabled(true)
        eventually { dnd.isOn() }
        assertTrue(dnd.toggle())
        eventually { !dnd.isDeviceDndOn() }
        assertTrue(store.isPaused())
        assertFalse(dnd.isManualDndRequested())
        assertTrue(dnd.toggle())
        eventually { dnd.isDeviceDndOn() }
        assertTrue("Manual DND can be on while place monitoring is paused", store.isPaused())
    }

    @Test fun systemDndIsDisplayedEvenWithoutAnAppRule() {
        shell("cmd notification set_dnd priority")
        eventually { dnd.isDeviceDndOn() }
        assertFalse(dnd.isOn())
        assertFalse("Other owners require Android settings", dnd.toggle())
        assertTrue(manager.automaticZenRules.isEmpty())
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun eventually(check: () -> Boolean) {
        repeat(50) { if (check()) return; Thread.sleep(100) }
        assertTrue("DND state did not settle", check())
    }
}
