package com.example.onetapdnd

import android.app.NotificationManager
import android.os.Build
import android.service.notification.Condition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a test device: these checks change the app's DND rules. */
@RunWith(AndroidJUnit4::class)
class DndControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val store = PlaceStore(context)

    @Before fun prepare() {
        instrumentation.uiAutomation.executeShellCommand("cmd notification allow_dnd ${context.packageName}").close()
        eventually { manager.isNotificationPolicyAccessGranted }
        clean()
    }

    @After fun clean() {
        store.preferences.edit().clear().commit()
        if (manager.isNotificationPolicyAccessGranted) {
            manager.automaticZenRules.keys.forEach { manager.removeAutomaticZenRule(it) }
        }
    }

    @Test fun freshControllerCanToggleSavedRuleOffAndOn() {
        DndController(context).toggle()
        eventually { DndController(context).isOn() }
        DndController(context).toggle()
        eventually { !DndController(context).isOn() }
        DndController(context).toggle()
        eventually { DndController(context).isOn() }
    }

    @Test fun overlappingPlacesAndManualOverride() {
        store.save(listOf(PlaceRule("a", "First", 0.0, 0.0, 200f),
            PlaceRule("b", "Second", 1.0, 1.0, 200f, totalSilence = true)))
        store.saveState(PlaceState(setOf("a", "b"), emptySet()))
        DndController(context).sync()
        eventually { DndController(context).isOn() }
        eventually { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE }
        store.saveState(store.state().transition(setOf("b"), false))
        DndController(context).sync()
        eventually { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY }
        DndController(context).toggle()
        eventually { !DndController(context).isOn() }
        assertEquals(setOf("a"), store.state().paused)
        store.saveState(store.state().transition(setOf("a"), true))
        DndController(context).sync()
        assertFalse(DndController(context).isOn())
        store.saveState(store.state().transition(setOf("a"), false).transition(setOf("a"), true))
        DndController(context).sync()
        eventually { DndController(context).isOn() }
    }

    @Test fun deletedPlaceEndsOnlyItsOwnMode() {
        DndController(context).toggle()
        eventually { DndController(context).isOn() }
        store.save(listOf(PlaceRule("a", "First", 0.0, 0.0, 200f, totalSilence = true)))
        store.saveState(PlaceState(setOf("a"), emptySet()))
        DndController(context).sync()
        eventually { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE }
        store.save(emptyList())
        DndController(context).sync()
        eventually { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY }
        assertTrue(DndController(context).isOn())
        if (Build.VERSION.SDK_INT >= 35) {
            val silence = manager.automaticZenRules.entries.first { it.value.conditionId.lastPathSegment == "silence" }
            assertEquals(Condition.STATE_FALSE, manager.getAutomaticZenRuleState(silence.key))
        }
    }

    private fun eventually(check: () -> Boolean) {
        repeat(40) { if (check()) return; Thread.sleep(100) }
        assertTrue("DND state did not settle", check())
    }
}
