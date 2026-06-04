package com.hermesandroid.bridge.accessibility

enum class ScreenWindowType { APPLICATION, SYSTEM, OTHER }

data class ScreenWindow(
    val type: ScreenWindowType,
    val isActive: Boolean,
    val isFocused: Boolean,
    val root: NodeView?,
)

object ScreenWindowSelector {
    fun select(windows: List<ScreenWindow>, includeSystemUi: Boolean): List<ScreenWindow> {
        val rooted = windows.filter { it.root != null }
        if (includeSystemUi) return rooted

        val apps = rooted.filter { it.type == ScreenWindowType.APPLICATION }
        return listOfNotNull(
            apps.firstOrNull { it.isActive },
            apps.firstOrNull { it.isFocused },
            apps.firstOrNull(),
            rooted.firstOrNull { it.isActive },
            rooted.firstOrNull { it.isFocused },
            rooted.firstOrNull(),
        ).distinct().take(1)
    }
}
