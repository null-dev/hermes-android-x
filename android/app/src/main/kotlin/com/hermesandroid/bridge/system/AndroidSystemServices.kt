package com.hermesandroid.bridge.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat

class AndroidSystemServices(private val context: Context) : SystemServices {

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun hasTelephony() =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    override fun readClipboard(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    override fun writeClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("hermes", text))
    }

    override fun sendIntent(action: String, data: String?, extras: Map<String, String>): Boolean = try {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (data != null) intent.data = Uri.parse(data)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        context.startActivity(intent)
        true
    } catch (e: Exception) { false }

    override fun sendBroadcast(action: String, extras: Map<String, String>): Boolean = try {
        val intent = Intent(action)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        context.sendBroadcast(intent)
        true
    } catch (e: Exception) { false }

    override fun sendSms(number: String, text: String): SmsResult {
        if (!hasTelephony()) return SmsResult.UNSUPPORTED
        if (!granted(android.Manifest.permission.SEND_SMS)) return SmsResult.NO_PERMISSION
        return try {
            val sms = context.getSystemService(SmsManager::class.java)
            sms.sendTextMessage(number, null, text, null, null)
            SmsResult.SENT
        } catch (e: Exception) { SmsResult.FAILED }
    }

    override fun startCall(number: String): CallResult {
        if (!hasTelephony()) return CallResult.UNSUPPORTED
        if (!granted(android.Manifest.permission.CALL_PHONE)) return CallResult.NO_PERMISSION
        return try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CallResult.STARTED
        } catch (e: Exception) { CallResult.NO_PERMISSION }
    }

    override fun searchContacts(query: String): List<Contact>? {
        if (!granted(android.Manifest.permission.READ_CONTACTS)) return null
        val results = mutableListOf<Contact>()
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        context.contentResolver.query(uri, projection, selection, arrayOf("%$query%"), null)?.use { c ->
            while (c.moveToNext()) results.add(Contact(c.getString(0) ?: "", c.getString(1) ?: ""))
        }
        return results
    }

    override fun lastLocation(): GeoLocation? {
        if (!granted(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            val providers = lm.getProviders(true)
            val best = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            best?.let { GeoLocation(it.latitude, it.longitude, it.accuracy) }
        } catch (e: SecurityException) { null }
    }

    override fun mediaAction(action: MediaAction): Boolean {
        // Dispatch a media key event via audio manager.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val keyCode = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) { false }
    }

    @Volatile private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false

    private fun ensureTts() {
        if (tts == null) {
            tts = android.speech.tts.TextToSpeech(context) { status ->
                ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            }
        }
    }

    override fun warmUpTts() { ensureTts() }

    override fun speak(text: String): Boolean {
        ensureTts()
        val engine = tts ?: return false
        if (!ttsReady) return false
        return engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "hermes") ==
            android.speech.tts.TextToSpeech.SUCCESS
    }

    override fun stopSpeaking() { tts?.stop() }
}
