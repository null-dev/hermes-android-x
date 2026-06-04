package com.hermesandroid.bridge.system

enum class MediaAction { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS, STOP }

enum class SmsResult { SENT, FAILED, NO_PERMISSION, UNSUPPORTED }
enum class CallResult { STARTED, NO_PERMISSION, UNSUPPORTED }

data class Contact(val name: String, val number: String)
data class GeoLocation(val latitude: Double, val longitude: Double, val accuracy: Float)

/** The Android system surface SystemController needs; real impl is device code. */
interface SystemServices {
    fun readClipboard(): String?
    fun writeClipboard(text: String)
    fun sendIntent(action: String, data: String?, extras: Map<String, String>): Boolean
    fun sendBroadcast(action: String, extras: Map<String, String>): Boolean
    fun sendSms(number: String, text: String): SmsResult
    fun startCall(number: String): CallResult
    /** null = unsupported or permission denied. */
    fun searchContacts(query: String): List<Contact>?
    /** null = unavailable (no permission / no fix). */
    fun lastLocation(): GeoLocation?
    fun mediaAction(action: MediaAction): Boolean
    fun speak(text: String): Boolean
    fun stopSpeaking()
}
