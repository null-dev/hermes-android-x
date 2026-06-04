package com.hermesandroid.bridge.system

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSys(
    var clip: String? = "hi",
    var sms: SmsResult = SmsResult.SENT,
    var call: CallResult = CallResult.STARTED,
    var contacts: List<Contact>? = listOf(Contact("Ann", "123")),
    var loc: GeoLocation? = GeoLocation(1.0, 2.0, 5f),
    var mediaOk: Boolean = true,
    var ttsOk: Boolean = true,
) : SystemServices {
    var wroteClip: String? = null
    var lastMedia: MediaAction? = null
    override fun readClipboard() = clip
    override fun writeClipboard(text: String) { wroteClip = text }
    override fun sendIntent(action: String, data: String?, extras: Map<String, String>) = true
    override fun sendBroadcast(action: String, extras: Map<String, String>) = true
    override fun sendSms(number: String, text: String) = sms
    override fun startCall(number: String) = call
    override fun searchContacts(query: String) = contacts
    override fun lastLocation() = loc
    override fun mediaAction(action: MediaAction): Boolean { lastMedia = action; return mediaOk }
    override fun speak(text: String) = ttsOk
    override fun stopSpeaking() {}
}

class SystemControllerTest {
    @Test fun clipboardReadReturnsText() {
        val r = SystemController(FakeSys(clip = "yo")).clipboardRead()
        assertEquals("yo", ((r as CommandResult.Ok).data as Map<*, *>)["text"])
    }
    @Test fun clipboardWriteStores() {
        val sys = FakeSys()
        SystemController(sys).clipboardWrite(Command.ClipboardWrite("copied"))
        assertEquals("copied", sys.wroteClip)
    }
    @Test fun smsSentOk() {
        val r = SystemController(FakeSys(sms = SmsResult.SENT)).sendSms(Command.SendSms("1", "hi"))
        assertTrue(r is CommandResult.Ok)
    }
    @Test fun smsNoPermissionMapsToError() {
        val r = SystemController(FakeSys(sms = SmsResult.NO_PERMISSION)).sendSms(Command.SendSms("1", "hi"))
        assertEquals("permission_denied", (r as CommandResult.Err).error)
    }
    @Test fun smsUnsupportedMapsToError() {
        val r = SystemController(FakeSys(sms = SmsResult.UNSUPPORTED)).sendSms(Command.SendSms("1", "hi"))
        assertEquals("unsupported", (r as CommandResult.Err).error)
    }
    @Test fun contactsUnsupportedWhenNull() {
        val r = SystemController(FakeSys(contacts = null)).searchContacts(Command.SearchContacts("a"))
        assertEquals("unsupported", (r as CommandResult.Err).error)
    }
    @Test fun locationUnavailableWhenNull() {
        val r = SystemController(FakeSys(loc = null)).location()
        assertEquals("location_unavailable", (r as CommandResult.Err).error)
    }
    @Test fun locationReturnsCoords() {
        val r = SystemController(FakeSys(loc = GeoLocation(10.0, 20.0, 3f))).location()
        assertEquals(10.0, ((r as CommandResult.Ok).data as Map<*, *>)["latitude"])
    }
    @Test fun mediaUnknownActionErrors() {
        val r = SystemController(FakeSys()).media(Command.MediaControl("rewind"))
        assertEquals("unknown_action", (r as CommandResult.Err).error)
    }
    @Test fun mediaDispatches() {
        val sys = FakeSys()
        SystemController(sys).media(Command.MediaControl("next"))
        assertEquals(MediaAction.NEXT, sys.lastMedia)
    }
    @Test fun speakUnavailableErrors() {
        val r = SystemController(FakeSys(ttsOk = false)).speak(Command.Speak("hi"))
        assertEquals("tts_unavailable", (r as CommandResult.Err).error)
    }
}
