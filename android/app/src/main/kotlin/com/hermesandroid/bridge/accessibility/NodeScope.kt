package com.hermesandroid.bridge.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Run [block] with [node], then recycle it — even if [block] throws. */
inline fun <R> useNode(node: AccessibilityNodeInfo, block: (AccessibilityNodeInfo) -> R): R {
    try {
        return block(node)
    } finally {
        @Suppress("DEPRECATION")
        node.recycle()
    }
}

/** Run [block], then recycle every window in [windows] — even if [block] throws. */
inline fun <R> withWindows(windows: List<AccessibilityWindowInfo>, block: () -> R): R {
    try {
        return block()
    } finally {
        for (w in windows) {
            @Suppress("DEPRECATION")
            w.recycle()
        }
    }
}
