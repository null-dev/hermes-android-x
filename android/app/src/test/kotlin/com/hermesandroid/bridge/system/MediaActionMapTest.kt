package com.hermesandroid.bridge.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaActionMapTest {
    @Test fun parsesKnown() {
        assertEquals(MediaAction.PLAY, MediaActionMap.parse("play"))
        assertEquals(MediaAction.PLAY_PAUSE, MediaActionMap.parse("play_pause"))
        assertEquals(MediaAction.NEXT, MediaActionMap.parse("NEXT"))
    }
    @Test fun unknownIsNull() { assertNull(MediaActionMap.parse("rewind")) }
}
