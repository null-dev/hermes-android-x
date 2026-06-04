package com.hermesandroid.bridge.event

data class NotificationRecord(
    val postedAt: Long,
    val packageName: String,
    val title: String?,
    val text: String?,
)

/** Bounded, thread-safe notification log. */
class NotificationStore(private val capacity: Int) {
    private val lock = Any()
    private val items = ArrayDeque<NotificationRecord>()

    fun add(record: NotificationRecord) = synchronized(lock) {
        items.addLast(record)
        while (items.size > capacity) items.removeFirst()
    }

    fun snapshot(): List<NotificationRecord> = synchronized(lock) { items.toList() }
}
