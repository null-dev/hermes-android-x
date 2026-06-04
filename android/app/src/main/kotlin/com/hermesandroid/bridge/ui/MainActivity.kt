package com.hermesandroid.bridge.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.lifecycle.BridgeForegroundService
import com.hermesandroid.bridge.lifecycle.LocalIp
import com.hermesandroid.bridge.server.PrefsTokenStorage
import com.hermesandroid.bridge.server.TokenStore

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val token = TokenStore(PrefsTokenStorage(this)).getOrCreate()
        val urls = LocalIp.all().joinToString("\n") { "http://$it:${BridgeForegroundService.PORT}" }
        findViewById<TextView>(R.id.tvUrls).text = urls
        findViewById<TextView>(R.id.tvToken).text = "Token: $token"

        findViewById<Button>(R.id.btnToggleBridge).setOnClickListener {
            if (BridgeForegroundService.isRunning) {
                BridgeForegroundService.stop(this)
            } else {
                BridgeForegroundService.start(this)
            }
        }

        findViewById<Button>(R.id.btnPermissions).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    private fun refreshStatus() {
        val running = BridgeForegroundService.isRunning
        val badge = findViewById<TextView>(R.id.tvStatusBadge)
        val btn = findViewById<Button>(R.id.btnToggleBridge)
        if (running) {
            badge.text = "● RUNNING"
            badge.setTextColor(Color.parseColor("#2E7D32"))
            btn.text = "Stop bridge"
        } else {
            badge.text = "● STOPPED"
            badge.setTextColor(Color.GRAY)
            btn.text = "Start bridge"
        }
    }
}
