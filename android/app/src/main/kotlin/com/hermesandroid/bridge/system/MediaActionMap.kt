package com.hermesandroid.bridge.system

object MediaActionMap {
    fun parse(s: String): MediaAction? = when (s.lowercase()) {
        "play" -> MediaAction.PLAY
        "pause" -> MediaAction.PAUSE
        "play_pause", "playpause", "toggle" -> MediaAction.PLAY_PAUSE
        "next" -> MediaAction.NEXT
        "previous", "prev" -> MediaAction.PREVIOUS
        "stop" -> MediaAction.STOP
        else -> null
    }
}
