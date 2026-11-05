package com.example.onetapdnd

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuietTimesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addDisableAndDeleteIndependentTimes() {
        val times = mutableStateOf(emptyList<DailyQuietTime>())
        compose.setContent {
            MaterialTheme { DailyQuietTimes(times.value, true, {}, { times.value = it }) }
        }
        repeat(2) {
            compose.onNodeWithText("+  Add quiet time").performClick()
            compose.onNodeWithText("New quiet time").assertIsDisplayed()
            compose.onNodeWithText("Save").performClick()
        }
        compose.runOnIdle {
            assertEquals(2, times.value.size)
            assertNotEquals(times.value[0].id, times.value[1].id)
        }
        compose.onAllNodes(hasContentDescription("Quiet time ", substring = true)).onFirst().performClick()
        compose.runOnIdle { assertEquals(1, times.value.count { it.enabled }) }
        compose.onAllNodes(hasContentDescription("Options for quiet time", substring = true)).onFirst().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(1, times.value.size) }
    }

    @Test fun storageMigratesOldWindowAndKeepsDeletionAfterReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        val settings = AutomationSettings(context)
        settings.preferences.edit().clear().putInt("start", 895).putInt("duration", 30).putBoolean("time_enabled", true).commit()
        try {
            assertEquals(DailyWindow(895, 30), settings.quietTimes.single().window)
            val added = DailyQuietTime("second", DailyWindow(480, 60), enabled = false)
            settings.quietTimes = settings.quietTimes + added
            val reloaded = AutomationSettings(context)
            assertEquals(2, reloaded.quietTimes.size)
            assertEquals(added, reloaded.quietTimes.last())
            settings.quietTimes = emptyList()
            assertTrue(AutomationSettings(context).quietTimes.isEmpty())
            settings.preferences.edit().clear().commit()
            settings.enabled = true
            assertTrue(AutomationSettings(context).quietTimes.isEmpty())
        } finally {
            settings.preferences.edit().clear().commit()
        }
    }
    @Test fun hybridOnlyAcceptsLocationDuringEnabledQuietWindows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        val settings = AutomationSettings(context)
        settings.preferences.edit().clear().commit()
        try {
            val now = System.currentTimeMillis()
            val minute = java.util.Calendar.getInstance().let {
                it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
            }
            settings.mode = AutomationMode.HYBRID
            settings.enabled = true
            settings.quietTimes = listOf(DailyQuietTime(window = DailyWindow((minute + 1439) % 1440, 3)))
            assertTrue(settings.locationChecksAllowed(now))
            assertTrue(settings.acceptsObservation(android.os.SystemClock.elapsedRealtimeNanos(), now))
            assertFalse(settings.acceptsObservation(android.os.SystemClock.elapsedRealtimeNanos() - 300_000_000_000L, now))
            settings.enabled = false
            assertFalse(settings.locationChecksAllowed(now))
            settings.enabled = true
            settings.quietTimes = listOf(DailyQuietTime(window = DailyWindow((minute + 60) % 1440, 3)))
            assertFalse(settings.locationChecksAllowed(now))
            settings.mode = AutomationMode.LOCATION
            assertTrue(settings.locationChecksAllowed(now))
            settings.mode = AutomationMode.TIME
            assertFalse(settings.locationChecksAllowed(now))
        } finally {
            settings.preferences.edit().clear().commit()
        }
    }

}
