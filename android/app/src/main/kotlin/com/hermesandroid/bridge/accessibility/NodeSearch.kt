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

    /** Pre-order list of all nodes. */
    private fun flatten(root: ScreenNode, out: MutableList<ScreenNode> = mutableListOf()): List<ScreenNode> {
        out.add(root)
        for (c in root.children) flatten(c, out)
        return out
    }

    fun byText(root: ScreenNode, query: String, exact: Boolean): List<ScreenNode> {
        val q = query.lowercase()
        return flatten(root).filter { n ->
            val t = n.text?.lowercase() ?: return@filter false
            if (exact) t == q else t.contains(q)
        }
    }

    fun matching(root: ScreenNode, text: String?, className: String?, clickableOnly: Boolean): List<ScreenNode> =
        flatten(root).filter { n ->
            (text == null || n.hasTextOrDescription(text)) &&
            (className == null || (n.className?.contains(className, ignoreCase = true) == true)) &&
            (!clickableOnly || n.clickable)
        }

    private fun ScreenNode.hasTextOrDescription(query: String): Boolean =
        text?.contains(query, ignoreCase = true) == true ||
            contentDescription?.contains(query, ignoreCase = true) == true
}
