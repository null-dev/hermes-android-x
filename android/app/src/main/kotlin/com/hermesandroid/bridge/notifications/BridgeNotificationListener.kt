package com.hermesandroid.bridge.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hermesandroid.bridge.event.EventBus
import com.hermesandroid.bridge.event.NotificationRecord
import java.util.concurrent.atomic.AtomicReference

class BridgeNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() { ref.set(this) }
    override fun onListenerDisconnected() { ref.compareAndSet(this, null) }
    override fun onDestroy() { ref.compareAndSet(this, null); super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val extras = n.notification.extras
        EventBus.notifications.add(
            NotificationRecord(
                postedAt = n.postTime,
                packageName = n.packageName,
                title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
                text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
            )
        )
    }

    companion object {
        // Kept for parity/observability; readers use EventBus, not this reference (bug #14).
        private val ref = AtomicReference<BridgeNotificationListener?>(null)
        fun isConnected(): Boolean = ref.get() != null
    }
}
