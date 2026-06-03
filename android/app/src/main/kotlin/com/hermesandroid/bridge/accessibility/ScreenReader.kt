package com.hermesandroid.bridge.accessibility

/** Builds an immutable ScreenNode snapshot from a NodeView tree, recycling as it goes. */
object ScreenReader {
    fun read(root: NodeView, includeBounds: Boolean): ScreenNode = build(root, "0", includeBounds)

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
