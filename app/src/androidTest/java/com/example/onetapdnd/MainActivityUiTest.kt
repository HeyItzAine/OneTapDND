package com.example.onetapdnd

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainActivityUiTest {
    @get:Rule
    val compose = createComposeRule()

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
            "Android requires this access before One Tap DND can change Do Not Disturb."
        ).assertCountEquals(0)
        compose.onNodeWithText("DND access").performClick()
        compose.onNodeWithText(
            "Android requires this access before One Tap DND can change Do Not Disturb."
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
