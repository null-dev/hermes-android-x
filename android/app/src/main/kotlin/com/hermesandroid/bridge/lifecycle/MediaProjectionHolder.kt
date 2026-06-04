package com.hermesandroid.bridge.lifecycle

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/** Stores the user's MediaProjection consent so the recorder can use it later. */
object MediaProjectionHolder {
    private var resultCode: Int = 0
    private var resultData: Intent? = null

    fun store(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.resultData = data
    }

    fun hasConsent(): Boolean = resultData != null

    /** Creates a fresh MediaProjection from stored consent, or null if not granted. */
    fun acquire(context: Context): MediaProjection? {
        val data = resultData ?: return null
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.getMediaProjection(resultCode, data)
    }
}
