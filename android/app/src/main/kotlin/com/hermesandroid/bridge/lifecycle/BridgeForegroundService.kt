package com.hermesandroid.bridge.lifecycle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.hermesandroid.bridge.server.BridgeServer
import com.hermesandroid.bridge.server.PrefsTokenStorage
import com.hermesandroid.bridge.server.TokenStore

class BridgeForegroundService : Service() {

    private var server: BridgeServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = TokenStore(PrefsTokenStorage(this)).getOrCreate()
        val url = "http://${LocalIp.best()}:$PORT"
        startForeground(NOTIF_ID, buildNotification(url))
        if (server == null) {
            server = BridgeServer(PORT, token).also { it.start() }
        }
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hermes Bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(url: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Hermes Bridge active")
            .setContentText(url)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

    companion object {
        const val PORT = 8765
        private const val CHANNEL = "hermes_bridge"
        private const val NOTIF_ID = 1

        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java)
            context.startForegroundService(intent)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeForegroundService::class.java))
        }
    }
}
