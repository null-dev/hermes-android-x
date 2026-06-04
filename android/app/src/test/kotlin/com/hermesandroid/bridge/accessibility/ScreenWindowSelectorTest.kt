package com.hermesandroid.bridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenWindowSelectorTest {

    private class FakeNode(private val label: String) : NodeView {
        override val text: String get() = label
        override val contentDescription: String? = null
        override val className: String? = "android.widget.TextView"
        override val viewId: String? = null
        override val clickable: Boolean = false
        override val bounds: NodeBounds = NodeBounds(0, 0, 10, 10)
        override val childCount: Int = 0
        override fun child(i: Int): NodeView? = null
        override fun recycle() {}
    }

    @Test
    fun defaultSelectionPrefersActiveApplicationWindow() {
        val app = ScreenWindow(
            type = ScreenWindowType.APPLICATION,
            isActive = true,
            isFocused = false,
            root = FakeNode("app"),
        )
        val system = ScreenWindow(
            type = ScreenWindowType.SYSTEM,
            isActive = true,
            isFocused = true,
            root = FakeNode("system"),
        )

        assertEquals(listOf(app), ScreenWindowSelector.select(listOf(system, app), includeSystemUi = false))
    }

    @Test
    fun includeSystemUiKeepsAllRootedWindows() {
        val app = ScreenWindow(ScreenWindowType.APPLICATION, isActive = true, isFocused = false, root = FakeNode("app"))
        val system = ScreenWindow(ScreenWindowType.SYSTEM, isActive = false, isFocused = true, root = FakeNode("system"))

        assertEquals(listOf(app, system), ScreenWindowSelector.select(listOf(app, system), includeSystemUi = true))
    }

    @Test
    fun fallsBackToActiveRootedWindowWhenNoApplicationWindowExists() {
        val system = ScreenWindow(ScreenWindowType.SYSTEM, isActive = true, isFocused = false, root = FakeNode("system"))
        val overlay = ScreenWindow(ScreenWindowType.OTHER, isActive = false, isFocused = true, root = FakeNode("overlay"))

        assertEquals(listOf(system), ScreenWindowSelector.select(listOf(overlay, system), includeSystemUi = false))
    }

    @Test
    fun singleSelectedWindowAddsMetadataToRoot() {
        val app = ScreenWindow(
            type = ScreenWindowType.APPLICATION,
            isActive = true,
            isFocused = false,
            layer = 3,
            title = "Calculator",
            rootPackageName = "com.android.calculator2",
            root = FakeNode("app"),
        )

        val tree = ScreenReader.readWindows(listOf(app), includeBounds = true)

        assertEquals("application", tree!!.window?.type)
        assertEquals(true, tree.window?.active)
        assertEquals(false, tree.window?.focused)
        assertEquals(3, tree.window?.layer)
        assertEquals("Calculator", tree.window?.title)
        assertEquals("com.android.calculator2", tree.window?.packageName)
    }

    @Test
    fun multipleWindowsAddMetadataToEachWindowChildOnly() {
        val app = ScreenWindow(ScreenWindowType.APPLICATION, isActive = true, isFocused = true, layer = 2, root = FakeNode("app"))
        val system = ScreenWindow(ScreenWindowType.SYSTEM, isActive = false, isFocused = false, layer = 9, root = FakeNode("system"))

        val tree = ScreenReader.readWindows(listOf(app, system), includeBounds = true)

        assertNull(tree!!.window)
        assertEquals("application", tree.children[0].window?.type)
        assertEquals(2, tree.children[0].window?.layer)
        assertEquals("system", tree.children[1].window?.type)
        assertEquals(9, tree.children[1].window?.layer)
    }
}
