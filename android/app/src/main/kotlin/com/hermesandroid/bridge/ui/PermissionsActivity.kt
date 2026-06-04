package com.hermesandroid.bridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.lifecycle.MediaProjectionHolder

class PermissionsActivity : AppCompatActivity() {

    private data class PermItem(
        val name: String,
        val description: String,
        val isGranted: () -> Boolean,
        val grant: () -> Unit,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Permissions"
    }

    override fun onResume() {
        super.onResume()
        rebuildList()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun rebuildList() {
        val container = findViewById<LinearLayout>(R.id.permissionsList)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        items().forEach { item ->
            val row = inflater.inflate(R.layout.item_permission, container, false)
            val granted = item.isGranted()
            row.findViewById<TextView>(R.id.tvPermIcon).text = if (granted) "✅" else "⚠️"
            row.findViewById<TextView>(R.id.tvPermName).text = item.name
            row.findViewById<TextView>(R.id.tvPermDesc).text = item.description
            val btn = row.findViewById<Button>(R.id.btnGrant)
            if (granted) {
                btn.visibility = View.GONE
            } else {
                btn.visibility = View.VISIBLE
                btn.setOnClickListener { item.grant() }
            }
            container.addView(row)
        }
    }

    private fun hasPermission(perm: String) =
        ActivityCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver,
            "enabled_notification_listeners") ?: return false
        return flat.contains(packageName, ignoreCase = true)
    }

    private fun items(): List<PermItem> = buildList {
        add(PermItem(
            name = "Accessibility Service",
            description = "Required for all screen reading and interaction tools",
            isGranted = ::isAccessibilityEnabled,
            grant = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        ))
        add(PermItem(
            name = "Notification Access",
            description = "Required for android_notifications",
            isGranted = ::isNotificationListenerEnabled,
            grant = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
        ))
        add(PermItem(
            name = "Screen Recording",
            description = "Required for android_screen_record",
            isGranted = { MediaProjectionHolder.hasConsent() },
            grant = { ScreenCaptureActivity.launch(this@PermissionsActivity) },
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermItem(
                name = "Post Notifications",
                description = "Required for the bridge status notification",
                isGranted = { hasPermission(Manifest.permission.POST_NOTIFICATIONS) },
                grant = { ActivityCompat.requestPermissions(this@PermissionsActivity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) },
            ))
        }
        add(PermItem(
            name = "SMS",
            description = "Required for android_send_sms",
            isGranted = { hasPermission(Manifest.permission.SEND_SMS) },
            grant = { ActivityCompat.requestPermissions(this@PermissionsActivity,
                arrayOf(Manifest.permission.SEND_SMS), REQ_RUNTIME) },
        ))
        add(PermItem(
            name = "Phone / Call",
            description = "Required for android_call",
            isGranted = { hasPermission(Manifest.permission.CALL_PHONE) },
            grant = { ActivityCompat.requestPermissions(this@PermissionsActivity,
                arrayOf(Manifest.permission.CALL_PHONE), REQ_RUNTIME) },
        ))
        add(PermItem(
            name = "Contacts",
            description = "Required for android_search_contacts",
            isGranted = { hasPermission(Manifest.permission.READ_CONTACTS) },
            grant = { ActivityCompat.requestPermissions(this@PermissionsActivity,
                arrayOf(Manifest.permission.READ_CONTACTS), REQ_RUNTIME) },
        ))
        add(PermItem(
            name = "Location",
            description = "Required for android_location",
            isGranted = { hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) },
            grant = { ActivityCompat.requestPermissions(this@PermissionsActivity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_RUNTIME) },
        ))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rebuildList()
    }

    companion object {
        private const val REQ_RUNTIME = 100
        private const val REQ_NOTIF = 101
    }
}
