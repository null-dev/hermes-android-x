package com.hermesandroid.bridge.accessibility

/** Pixel bounds of a node on screen. */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

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
)

/** Stable 64-bit FNV-1a content hash of a screen, for change detection. */
object ScreenHash {
    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L

    fun hash(root: ScreenNode): String {
        var h = FNV_OFFSET
        fun mix(s: String?) {
            val str = s ?: ""
            for (c in str) {
                h = h xor c.code.toLong()
                h *= FNV_PRIME
            }
            // field separator so "ab"+"c" != "a"+"bc"
            h = h xor 0x1fL
            h *= FNV_PRIME
        }
        fun walk(n: ScreenNode) {
            mix(n.text); mix(n.contentDescription); mix(n.className); mix(n.viewId)
            mix(if (n.clickable) "1" else "0")
            mix("${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}")
            mix("(") // structure markers make sibling order significant
            for (child in n.children) walk(child)
            mix(")")
        }
        walk(root)
        return java.lang.Long.toHexString(h)
    }
}
