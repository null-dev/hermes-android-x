package com.hermesandroid.bridge.accessibility

import java.security.MessageDigest

/** Pixel bounds of a node on screen. */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Metadata for the accessibility window that owns a top-level screen subtree. */
data class WindowInfo(
    val type: String,
    val active: Boolean,
    val focused: Boolean,
    val layer: Int,
    val title: String?,
    val packageName: String?,
)

/** Immutable snapshot of one accessibility node and its subtree. */
data class ScreenNode(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val clickable: Boolean,
    val bounds: NodeBounds,
    val children: List<ScreenNode>,
    val window: WindowInfo? = null,
)

/** Stable SHA-256 content digest of a screen, for change detection. */
object ScreenHash {
    fun hash(root: ScreenNode): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun mix(s: String?) {
            val str = s ?: ""
            digest.update(str.length.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(str.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }

        fun walk(n: ScreenNode) {
            mix(n.text); mix(n.contentDescription); mix(n.className); mix(n.viewId)
            mix(n.window?.type); mix(n.window?.title); mix(n.window?.packageName)
            mix(n.window?.active?.toString())
            mix(n.window?.focused?.toString())
            mix(n.window?.layer?.toString())
            mix(if (n.clickable) "1" else "0")
            mix("${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}")
            mix("(") // structure markers make sibling order significant
            for (child in n.children) walk(child)
            mix(")")
        }
        walk(root)
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
