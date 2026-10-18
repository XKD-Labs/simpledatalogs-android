package com.xkdlabs.simpledatalogs

/** One tracked event, stamped at `track` time and sent as one NDJSON line. */
internal data class TrackedEvent(
    /** The event name, e.g. `level_complete`. */
    val eventName: String,

    /** The per-event UUID that makes retries safe to dedupe. */
    val eventId: String,

    /** When the host called `track`, in epoch milliseconds. */
    val occurredAtMillis: Long,

    /** The anonymous device id, when the host set one. */
    val deviceId: String?,

    /** The session id, when autocapture's sessions are on. */
    val sessionId: String?,

    /** Default properties merged with the event's own properties. */
    val properties: Map<String, Any>,
) {
    /** Encodes the event as one compact JSON object with no trailing newline. */
    fun encodedLine(): String {
        val wireFields = linkedMapOf<String, Any?>(
            "event" to eventName,
            "event_id" to eventId,
            "ts" to Timestamps.formatIso8601Utc(occurredAtMillis),
            "device_id" to deviceId,
            "session_id" to sessionId,
            "props" to properties,
        )

        return JsonWriter.encodeObject(wireFields)
    }
}
