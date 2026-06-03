package com.hermesandroid.bridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenHashTest {

    private fun leaf(text: String? = null, id: String = "0"): ScreenNode =
        ScreenNode(
            id = id, text = text, contentDescription = null, className = "android.widget.TextView",
            viewId = null, clickable = false, bounds = NodeBounds(0, 0, 10, 10), children = emptyList(),
        )

    private fun parentOf(vararg kids: ScreenNode): ScreenNode =
        ScreenNode("p", null, null, "android.widget.LinearLayout", null, false,
            NodeBounds(0, 0, 100, 100), kids.toList())

    @Test
    fun deterministic() {
        val tree = parentOf(leaf("a"), leaf("b"))
        assertEquals(ScreenHash.hash(tree), ScreenHash.hash(tree))
    }

    @Test
    fun sensitiveToText() {
        assertNotEquals(ScreenHash.hash(leaf("hello")), ScreenHash.hash(leaf("world")))
    }

    @Test
    fun siblingOrderMatters() {
        assertNotEquals(
            ScreenHash.hash(parentOf(leaf("a"), leaf("b"))),
            ScreenHash.hash(parentOf(leaf("b"), leaf("a"))),
        )
    }

    @Test
    fun noCollisionsAcrossManyDistinctTrees() {
        val hashes = (0 until 5000).map { ScreenHash.hash(leaf("item $it")) }.toSet()
        assertEquals(5000, hashes.size)
    }
}
