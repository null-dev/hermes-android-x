package com.hermesandroid.bridge.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** The minimal slice of an accessibility node that ScreenReader needs. */
interface NodeView {
    val text: String?
    val contentDescription: String?
    val className: String?
    val viewId: String?
    val clickable: Boolean
    val bounds: NodeBounds
    val childCount: Int
    fun child(i: Int): NodeView?
    fun recycle()
}

/** Production adapter wrapping a real AccessibilityNodeInfo. */
class AndroidNodeView(private val node: AccessibilityNodeInfo) : NodeView {
    override val text get() = node.text?.toString()
    override val contentDescription get() = node.contentDescription?.toString()
    override val className get() = node.className?.toString()
    override val viewId get() = node.viewIdResourceName
    override val clickable get() = node.isClickable
    override val bounds: NodeBounds
        get() {
            val r = Rect()
            node.getBoundsInScreen(r)
            return NodeBounds(r.left, r.top, r.right, r.bottom)
        }
    override val childCount get() = node.childCount
    override fun child(i: Int): NodeView? = node.getChild(i)?.let { AndroidNodeView(it) }
    @Suppress("DEPRECATION")
    override fun recycle() = node.recycle()
}
