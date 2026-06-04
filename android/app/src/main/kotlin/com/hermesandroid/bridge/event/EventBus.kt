package com.hermesandroid.bridge.event

/** Process-wide stores. Always present, so readers never see a null service instance. */
object EventBus {
    val events = EventStore(capacity = 500)
    val notifications = NotificationStore(capacity = 200)
}
