package com.hermesandroid.bridge.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NodeScopeTest {

    @Test
    fun useNodeRecyclesOnNormalReturn() {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        val r = useNode(node) { 42 }
        assertEquals(42, r)
        verify(exactly = 1) { node.recycle() }
    }

    @Test
    fun useNodeRecyclesEvenWhenBlockThrows() {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        try {
            useNode(node) { throw RuntimeException("boom") }
            fail("expected exception to propagate")
        } catch (_: RuntimeException) { /* expected */ }
        verify(exactly = 1) { node.recycle() }
    }

    @Test
    fun withWindowsRecyclesAllEvenWhenBlockThrows() {
        val w1 = mockk<AccessibilityWindowInfo>(relaxed = true)
        val w2 = mockk<AccessibilityWindowInfo>(relaxed = true)
        try {
            withWindows(listOf(w1, w2)) { throw RuntimeException("boom") }
            fail("expected exception to propagate")
        } catch (_: RuntimeException) { /* expected */ }
        verify(exactly = 1) { w1.recycle() }
        verify(exactly = 1) { w2.recycle() }
    }
}
