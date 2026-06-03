package com.hermesandroid.bridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderTest {

    /** A fake node tree that records which nodes were recycled. */
    private class FakeNode(
        val label: String,
        override val clickable: Boolean = false,
        val kids: MutableList<FakeNode> = mutableListOf(),
        val recycled: MutableList<String>,
    ) : NodeView {
        override val text get() = label
        override val contentDescription: String? = null
        override val className = "android.widget.TextView"
        override val viewId: String? = null
        override val bounds = NodeBounds(0, 0, 10, 10)
        override val childCount get() = kids.size
        override fun child(i: Int): NodeView = kids[i]
        override fun recycle() { recycled.add(label) }
    }

    @Test
    fun buildsTreeWithDottedIdPaths() {
        val recycled = mutableListOf<String>()
        val root = FakeNode("root", recycled = recycled).apply {
            kids.add(FakeNode("a", clickable = true, recycled = recycled))
            kids.add(FakeNode("b", recycled = recycled))
        }
        val tree = ScreenReader.read(root, includeBounds = true)

        assertEquals("0", tree.id)
        assertEquals("root", tree.text)
        assertEquals(2, tree.children.size)
        assertEquals("0.0", tree.children[0].id)
        assertEquals("a", tree.children[0].text)
        assertTrue(tree.children[0].clickable)
        assertEquals("0.1", tree.children[1].id)
    }

    @Test
    fun recyclesEveryNodeEvenNested() {
        val recycled = mutableListOf<String>()
        val root = FakeNode("root", recycled = recycled).apply {
            kids.add(FakeNode("a", recycled = recycled))
        }
        ScreenReader.read(root, includeBounds = true)
        assertEquals(setOf("root", "a"), recycled.toSet())
    }

    @Test
    fun omitsBoundsWhenNotRequested() {
        val recycled = mutableListOf<String>()
        val root = FakeNode("root", recycled = recycled)
        val tree = ScreenReader.read(root, includeBounds = false)
        assertEquals(NodeBounds(0, 0, 0, 0), tree.bounds)
    }
}
