package com.xkdlabs.simpledatalogs

/** The session state machine: active and background moments in, session_start and session_end out. */
@InternalSimpleDataLogsApi
public class SessionTracker(
    /** The current session, if any, e.g. one a previous launch saved. */
    session: Session? = null,
) {
    /** A session as stored between launches. */
    public data class Session(
        /** The id sent as `session_id`. */
        val id: String,

        /** When the session began, in epoch milliseconds. */
        val startedAtMillis: Long,

        /** The last moment the app was active, or when it went to the background. */
        val lastActiveAtMillis: Long,

        /** True while the app is in the background. */
        val isInBackground: Boolean,
    ) {
        /** Encodes the session for `session.json`. */
        public fun toJson(): String {
            return JsonWriter.encodeObject(
                linkedMapOf(
                    "id" to id,
                    "started_at_ms" to startedAtMillis,
                    "last_active_at_ms" to lastActiveAtMillis,
                    "is_in_background" to isInBackground,
                ),
            )
        }

        /** Decodes `session.json`. */
        public companion object {
            /** Reads a session saved by `toJson`, or null when the text is not one. */
            public fun fromJsonOrNull(sessionJson: String): Session? {
                val sessionFields = JsonReader.parseObjectOrNull(sessionJson) ?: return null

                val sessionId = sessionFields["id"] as? String ?: return null
                val startedAtMillis = sessionFields["started_at_ms"] as? Long ?: return null
                val lastActiveAtMillis = sessionFields["last_active_at_ms"] as? Long ?: return null
                val isInBackground = sessionFields["is_in_background"] as? Boolean ?: return null

                return Session(sessionId, startedAtMillis, lastActiveAtMillis, isInBackground)
            }
        }
    }

    /** What a moment produced, in order. */
    public sealed class Change {
        /** A session began. */
        public data class Started(val sessionId: String, val atMillis: Long) : Change()

        /** A session ended at its last activity, after this many seconds. */
        public data class Ended(val sessionId: String, val atMillis: Long, val durationSeconds: Long) : Change()
    }

    /** The timeout. */
    public companion object {
        /** Time away that ends a session: 30 minutes, as in most app analytics. */
        public const val TIMEOUT_MILLIS: Long = 30 * 60 * 1_000
    }

    /** The current session, if any. */
    public var session: Session? = session
        private set

    /** The app became active: ends a session idle for the whole timeout, then starts one or resumes the current one. */
    public fun appBecameActive(nowMillis: Long, newSessionId: () -> String): List<Change> {
        val changes = ArrayList<Change>()

        // A session away for the whole timeout ends where its activity stopped.
        val currentSession = session
        if (currentSession != null && nowMillis - currentSession.lastActiveAtMillis >= TIMEOUT_MILLIS) {
            val durationSeconds = maxOf(0L, Math.round((currentSession.lastActiveAtMillis - currentSession.startedAtMillis) / 1_000.0))
            changes.add(Change.Ended(currentSession.id, currentSession.lastActiveAtMillis, durationSeconds))
            session = null
        }

        // No session: start one; otherwise resume it.
        val resumableSession = session
        if (resumableSession == null) {
            val startedSession = Session(newSessionId(), nowMillis, nowMillis, isInBackground = false)
            session = startedSession
            changes.add(Change.Started(startedSession.id, nowMillis))
        } else {
            session = resumableSession.copy(lastActiveAtMillis = nowMillis, isInBackground = false)
        }

        return changes
    }

    /** The app went to the background: remembers when, so the next activity can decide whether the session ended. */
    public fun appWentToBackground(nowMillis: Long) {
        session = session?.copy(lastActiveAtMillis = nowMillis, isInBackground = true)
    }
}
