package com.example.onetapdnd

import android.Manifest
import android.app.NotificationManager
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the debug app's places and restores the phone's starting ringer. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PhoneSilentModeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val store = PlaceStore(context)

    @Test fun silentPlaceChangesTheInternalRingerAndRestoresIt() {
        assumeTrue(context.packageName.endsWith(".debug"))
        assumeTrue("Start this device check with DND off", manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
        val original = internalRinger()
        shell("cmd notification allow_dnd ${context.packageName}")
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION).forEach {
            shell("pm grant ${context.packageName} $it")
        }
        try {
            listOf("NORMAL", "VIBRATE", "SILENT").forEach { starting ->
                clearTestPlace()
                shell("cmd audio set-ringer-mode $starting")
                val rule = PlaceRule("phone-silent-test", "Silent test", 0.0, 0.0, 200f,
                    PlaceAudioMode.DND_AND_SILENT)
                store.save(listOf(rule))
                store.saveState(PlaceState(setOf(rule.id)))
                MonitoringCoordinator(context).reconcile()
                eventually("internal Silent from $starting; public=${audio.ringerMode}") {
                    internalRinger() == "SILENT"
                }
                MonitoringCoordinator(context).reconcile()
                assertEquals("Silent survives another check", "SILENT", internalRinger())
                listOf(PlaceAudioMode.DND_AND_SOUND, PlaceAudioMode.DND_AND_VIBRATE).forEach { other ->
                    store.save(listOf(rule.copy(audioMode = other)))
                    MonitoringCoordinator(context).reconcile()
                    store.save(listOf(rule))
                    MonitoringCoordinator(context).reconcile()
                    eventually("switch from ${other.ringer} to internal Silent") { internalRinger() == "SILENT" }
                }
                val holdSeconds = InstrumentationRegistry.getArguments().getString("holdSilentSeconds")
                    ?.toLongOrNull()?.coerceIn(0, 30) ?: 0
                if (starting == "VIBRATE") Thread.sleep(holdSeconds * 1_000)
                store.clearPosition()
                MonitoringCoordinator(context).reconcile()
                eventually("restore internal $starting on exit") { internalRinger() == starting }
                assertFalse("place DND is released", DndController(context).isOn())
                if (starting != "SILENT") {
                    eventually("system DND off after restoring $starting") {
                        manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                    }
                }
            }
        } finally {
            clearTestPlace()
            // External Sound also clears system DND introduced by a Silent write.
            // The precondition requires DND off, so restore that before the ringer.
            audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            shell("cmd audio set-ringer-mode $original")
        }
    }

    private fun clearTestPlace() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        store.preferences.edit().clear().commit()
        manager.automaticZenRules.keys.forEach(manager::removeAutomaticZenRule)
    }

    private fun internalRinger(): String {
        val dump = shell("dumpsys audio")
        return Regex("(?m)^- mode \\(internal\\) = (NORMAL|VIBRATE|SILENT)\\s*$")
            .find(dump)?.groupValues?.get(1)
            ?: error("AudioService did not expose its internal ringer mode")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun eventually(message: String, check: () -> Boolean) {
        repeat(30) {
            if (check()) return
            Thread.sleep(100)
        }
        assertTrue("$message; active=${store.state().inside}; mode=${MonitoringCoordinator(context).effectiveAudioMode()}; error=${store.audioError()}", check())
    }
}
