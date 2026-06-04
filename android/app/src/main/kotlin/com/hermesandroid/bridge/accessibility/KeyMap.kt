package com.hermesandroid.bridge.accessibility

import android.accessibilityservice.AccessibilityService

/** Maps friendly key names to AccessibilityService global actions. */
object KeyMap {
    fun globalAction(key: String): Int? = when (key.lowercase()) {
        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
        "home" -> AccessibilityService.GLOBAL_ACTION_HOME
        "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
        "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        else -> null
    }
}
