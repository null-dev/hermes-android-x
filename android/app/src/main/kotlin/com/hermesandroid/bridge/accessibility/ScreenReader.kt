package com.hermesandroid.bridge.accessibility

/** Builds an immutable ScreenNode snapshot from a NodeView tree, recycling as it goes. */
object ScreenReader {
    fun read(root: NodeView, includeBounds: Boolean): ScreenNode = build(root, "0", includeBounds)

    fun readRoots(roots: List<NodeView>, includeBounds: Boolean): ScreenNode? {
        if (roots.isEmpty()) return null
        if (roots.size == 1) return build(roots.single(), "0", includeBounds)

        val children = roots.mapIndexed { i, root -> build(root, "0.$i", includeBounds) }
        val bounds = if (includeBounds) unionBounds(children) else NodeBounds(0, 0, 0, 0)
        return ScreenNode(
            id = "0",
            text = null,
            contentDescription = null,
            className = "android.view.WindowRoot",
            viewId = null,
            clickable = false,
            bounds = bounds,
            children = children,
        )
    }

    private fun unionBounds(nodes: List<ScreenNode>): NodeBounds {
        val left = nodes.minOf { it.bounds.left }
        val top = nodes.minOf { it.bounds.top }
        val right = nodes.maxOf { it.bounds.right }
        val bottom = nodes.maxOf { it.bounds.bottom }
        return NodeBounds(left, top, right, bottom)
    }

    private fun build(view: NodeView, id: String, includeBounds: Boolean): ScreenNode {
        try {
            val children = ArrayList<ScreenNode>(view.childCount)
            for (i in 0 until view.childCount) {
                val child = view.child(i) ?: continue
                children.add(build(child, "$id.$i", includeBounds))
            }
            return ScreenNode(
                id = id,
                text = view.text,
                contentDescription = view.contentDescription,
                className = view.className,
                viewId = view.viewId,
                clickable = view.clickable,
                bounds = if (includeBounds) view.bounds else NodeBounds(0, 0, 0, 0),
                children = children,
            )
        } finally {
            view.recycle()
        }
    }
}
