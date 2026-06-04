package com.hermesandroid.bridge.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.lifecycle.BridgeForegroundService
import com.hermesandroid.bridge.lifecycle.LocalIp
import com.hermesandroid.bridge.server.PrefsTokenStorage
import com.hermesandroid.bridge.server.TokenStore

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val token = TokenStore(PrefsTokenStorage(this)).getOrCreate()
        findViewById<TextView>(R.id.tvUrl).text =
            "URL: http://${LocalIp.best()}:${BridgeForegroundService.PORT}"
        findViewById<TextView>(R.id.tvToken).text = "Token: $token"

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            BridgeForegroundService.start(this)
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAccessibility).setOnLongClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            true
        }
        findViewById<Button>(R.id.btnScreenRecord).setOnClickListener {
            com.hermesandroid.bridge.ui.ScreenCaptureActivity.launch(this)
        }

        requestPermissions(
            arrayOf(
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            42,
        )
    }
}
