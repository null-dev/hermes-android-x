package com.hermesandroid.bridge.accessibility

/** Center point of a node's bounds. */
fun ScreenNode.center(): Pair<Int, Int> =
    (bounds.left + bounds.right) / 2 to (bounds.top + bounds.bottom) / 2

/** Pure searches over an immutable ScreenNode tree. */
object NodeSearch {
    fun byId(root: ScreenNode, id: String): ScreenNode? {
        if (root.id == id) return root
        for (child in root.children) byId(child, id)?.let { return it }
        return null
    }
}
