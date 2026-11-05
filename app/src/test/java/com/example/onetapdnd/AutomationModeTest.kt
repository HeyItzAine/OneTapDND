package com.example.onetapdnd

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationModeTest {
    @Test
    fun hybridRequiresBothConditionsAndSingleModesUseTheirOwnCondition() {
        for (time in listOf(false, true)) {
            for (location in listOf(false, true)) {
                assertEquals(time && location, automationActive(AutomationMode.HYBRID, time, location))
                assertEquals(time, automationActive(AutomationMode.TIME, time, location))
                assertEquals(location, automationActive(AutomationMode.LOCATION, time, location))
            }
        }
    }
}
