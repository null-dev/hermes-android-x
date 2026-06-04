package com.hermesandroid.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyMapTest {
    @Test fun mapsKnownKeys() {
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, KeyMap.globalAction("back"))
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, KeyMap.globalAction("HOME"))
        assertEquals(AccessibilityService.GLOBAL_ACTION_RECENTS, KeyMap.globalAction("recents"))
    }
    @Test fun unknownKeyIsNull() { assertNull(KeyMap.globalAction("teleport")) }
}
