package com.example.onetapdnd

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainActivityUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ringerSelectorHasThreeAccessibleChoicesAndUpdatesSelection() {
        val selected = mutableStateOf(RingerMode.SOUND)
        compose.setContent {
            MaterialTheme { RingerModeSelector(selected.value) { selected.value = it } }
        }
        listOf(RingerMode.SILENT, RingerMode.VIBRATE, RingerMode.SOUND).forEach { mode ->
            compose.onNodeWithContentDescription(mode.label).assertIsDisplayed().performClick().assertIsSelected()
            RingerMode.entries.filter { it != mode }.forEach {
                compose.onNodeWithContentDescription(it.label).assertIsNotSelected()
            }
            compose.runOnIdle { assertEquals(mode, selected.value) }
        }
    }

    @Test
    fun setupDetailsStayHiddenUntilCardIsOpened() {
        compose.setContent {
            MaterialTheme {
                SetupScreen(
                    dndGranted = true,
                    notificationsGranted = true,
                    selectedIcon = IconStyle.BLACK,
                    onGrantDnd = {},
                    onGrantNotifications = {},
                    onAddTile = {},
                    onIconStyleSelected = {}
                )
            }
        }

        compose.onAllNodesWithText(
            "Android requires this access before One Tap DND can change Do Not Disturb or set the ringer to silent."
        ).assertCountEquals(0)
        compose.onNodeWithText("DND access").performClick()
        compose.onNodeWithText(
            "Android requires this access before One Tap DND can change Do Not Disturb or set the ringer to silent."
        ).assertIsDisplayed()
    }

    @Test
    fun iconChoicesAreStackedInsideExpandableCard() {
        var selected: IconStyle? = null
        compose.setContent {
            MaterialTheme {
                SetupScreen(
                    dndGranted = true,
                    notificationsGranted = true,
                    selectedIcon = IconStyle.WHITE,
                    onGrantDnd = {},
                    onGrantNotifications = {},
                    onAddTile = {},
                    onIconStyleSelected = { selected = it }
                )
            }
        }

        compose.onAllNodesWithText("Black on white").assertCountEquals(0)
        compose.onNodeWithText("App icon").performClick()
        compose.onNodeWithText("Black on white").assertIsDisplayed().performClick()
        compose.onNodeWithText("White on black").assertIsDisplayed()
        compose.runOnIdle { assertEquals(IconStyle.BLACK, selected) }
    }
}
