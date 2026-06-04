package com.hermesandroid.bridge.system

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult

/** Pure mapping from system/comms commands to results; Android specifics via [sys]. */
class SystemController(private val sys: SystemServices) {

    fun clipboardRead(): CommandResult = CommandResult.Ok(mapOf("text" to sys.readClipboard()))

    fun clipboardWrite(cmd: Command.ClipboardWrite): CommandResult {
        sys.writeClipboard(cmd.text)
        return CommandResult.Ok(mapOf("written" to true))
    }

    fun sendIntent(cmd: Command.SendIntent): CommandResult =
        if (sys.sendIntent(cmd.action, cmd.data, cmd.extras)) CommandResult.Ok(mapOf("sent" to true))
        else CommandResult.Err("intent_failed", "could not start intent ${cmd.action}")

    fun broadcast(cmd: Command.Broadcast): CommandResult =
        if (sys.sendBroadcast(cmd.action, cmd.extras)) CommandResult.Ok(mapOf("sent" to true))
        else CommandResult.Err("broadcast_failed", "could not send broadcast ${cmd.action}")

    fun sendSms(cmd: Command.SendSms): CommandResult = when (sys.sendSms(cmd.number, cmd.text)) {
        SmsResult.SENT -> CommandResult.Ok(mapOf("sent" to true))
        SmsResult.FAILED -> CommandResult.Err("sms_failed", "sending failed")
        SmsResult.NO_PERMISSION -> CommandResult.Err("permission_denied", "SMS permission not granted")
        SmsResult.UNSUPPORTED -> CommandResult.Err("unsupported", "device has no telephony")
    }

    fun call(cmd: Command.Call): CommandResult = when (sys.startCall(cmd.number)) {
        CallResult.STARTED -> CommandResult.Ok(mapOf("calling" to cmd.number))
        CallResult.NO_PERMISSION -> CommandResult.Err("permission_denied", "Call permission not granted")
        CallResult.UNSUPPORTED -> CommandResult.Err("unsupported", "device has no telephony")
    }

    fun searchContacts(cmd: Command.SearchContacts): CommandResult {
        val list = sys.searchContacts(cmd.query)
            ?: return CommandResult.Err("unsupported", "contacts unavailable or permission denied")
        return CommandResult.Ok(mapOf("contacts" to list.map { mapOf("name" to it.name, "number" to it.number) }))
    }

    fun location(): CommandResult {
        val loc = sys.lastLocation()
            ?: return CommandResult.Err("location_unavailable", "no location permission or fix")
        return CommandResult.Ok(mapOf("latitude" to loc.latitude, "longitude" to loc.longitude, "accuracy" to loc.accuracy))
    }

    fun media(cmd: Command.MediaControl): CommandResult {
        val action = MediaActionMap.parse(cmd.action)
            ?: return CommandResult.Err("unknown_action", "no media action '${cmd.action}'")
        return if (sys.mediaAction(action)) CommandResult.Ok(mapOf("media" to action.name))
        else CommandResult.Err("media_failed", "no active media session")
    }

    fun speak(cmd: Command.Speak): CommandResult =
        if (sys.speak(cmd.text)) CommandResult.Ok(mapOf("speaking" to true))
        else CommandResult.Err("tts_unavailable", "text-to-speech not ready")

    fun stopSpeaking(): CommandResult {
        sys.stopSpeaking()
        return CommandResult.Ok(mapOf("stopped" to true))
    }

    fun warmUpTts() = sys.warmUpTts()
}
