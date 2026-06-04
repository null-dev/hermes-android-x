package com.hermesandroid.bridge.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.lifecycle.BridgeForegroundService
import com.hermesandroid.bridge.server.PrefsTokenStorage
import com.hermesandroid.bridge.server.TokenStore

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("regen_token")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                TokenStore(PrefsTokenStorage(ctx)).regenerate()
                if (BridgeForegroundService.isRunning) {
                    BridgeForegroundService.stop(ctx)
                    BridgeForegroundService.start(ctx)
                }
                Toast.makeText(ctx, "Token regenerated — update your agent config", Toast.LENGTH_LONG).show()
                true
            }
        }
    }
}
