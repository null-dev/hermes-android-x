package com.hermesandroid.bridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeSearchTest {
    private fun node(id: String, text: String?, cls: String, clickable: Boolean, kids: List<ScreenNode> = emptyList()) =
        ScreenNode(id, text, null, cls, null, clickable, NodeBounds(0, 0, 10, 10), kids)

    private val tree = node("0", null, "Root", false, listOf(
        node("0.0", "Submit", "android.widget.Button", true),
        node("0.1", "submit form", "android.widget.TextView", false),
        node("0.2", "Cancel", "android.widget.Button", true),
    ))

    @Test fun byIdFindsNested() { assertEquals("Cancel", NodeSearch.byId(tree, "0.2")?.text) }
    @Test fun byIdMissingIsNull() { assertNull(NodeSearch.byId(tree, "9.9")) }

    @Test fun byTextExactMatchesWholeStringCaseInsensitive() {
        val hits = NodeSearch.byText(tree, "submit", exact = true)
        assertEquals(listOf("0.0"), hits.map { it.id })
    }

    @Test fun byTextSubstringMatchesContains() {
        val hits = NodeSearch.byText(tree, "submit", exact = false).map { it.id }
        assertEquals(listOf("0.0", "0.1"), hits)
    }

    @Test fun matchingFiltersByClassAndClickable() {
        val hits = NodeSearch.matching(tree, text = null, className = "Button", clickableOnly = true).map { it.id }
        assertEquals(listOf("0.0", "0.2"), hits)
    }
}
