package com.hermesandroid.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.bridge.accessibility.BridgeAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotInstrumentedTest {
    @Test
    fun capturesNonEmptyPng() {
        val svc = BridgeAccessibilityService.current()
        assumeTrue("enable accessibility service", svc != null)
        val png = runBlocking { svc!!.takeScreenshotPng() }
        assertTrue("expected PNG bytes", png != null && png.size > 100)
    }
}
