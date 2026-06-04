package com.hermesandroid.bridge.event

/** One accessibility/UI event. [seq] is assigned by the store on append. */
data class EventRecord(
    val seq: Long,
    val timestamp: Long,
    val type: String,
    val packageName: String?,
    val text: String?,
)

/**
 * Bounded, thread-safe event log. Append + trim happen atomically under one lock
 * (fixes the prototype's check-then-act capacity race, bug #3).
 */
class EventStore(private val capacity: Int) {
    private val lock = Any()
    private val items = ArrayDeque<EventRecord>()
    private var nextSeq = 1L

    fun append(record: EventRecord): EventRecord = synchronized(lock) {
        val stamped = record.copy(seq = nextSeq++)
        items.addLast(stamped)
        while (items.size > capacity) items.removeFirst()
        stamped
    }

    fun since(seq: Long): List<EventRecord> = synchronized(lock) {
        items.filter { it.seq > seq }
    }

    fun snapshot(): List<EventRecord> = synchronized(lock) { items.toList() }
}
