package com.hermesandroid.bridge.event

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationStoreTest {
    @Test fun boundedAndNewestRetained() {
        val s = NotificationStore(capacity = 3)
        repeat(5) { s.add(NotificationRecord(it.toLong(), "pkg", "title$it", "body")) }
        val snap = s.snapshot()
        assertEquals(3, snap.size)
        assertEquals("title4", snap.last().title)
    }
}
