package com.hermesandroid.bridge.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay
import java.io.File

/** Records the screen to an MP4 for a fixed duration using a MediaProjection. */
class ScreenRecorder(private val context: Context) {

    suspend fun record(projection: MediaProjection, durationMs: Long): ByteArray? {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val file = File.createTempFile("rec", ".mp4", context.cacheDir)

        val recorder = MediaRecorder(context).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(metrics.widthPixels, metrics.heightPixels)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(4_000_000)
            setOutputFile(file.absolutePath)
            prepare()
        }
        var display: VirtualDisplay? = null
        return try {
            display = projection.createVirtualDisplay(
                "hermes-rec", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.surface, null, null,
            )
            recorder.start()
            delay(durationMs)
            recorder.stop()
            file.readBytes()
        } catch (e: Exception) {
            null
        } finally {
            try { recorder.reset(); recorder.release() } catch (_: Exception) {}
            display?.release()
            projection.stop()
            file.delete() // on-device temp removed immediately after reading (bug #12, device side)
        }
    }
}
