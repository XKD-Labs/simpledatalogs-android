package com.xkdlabs.simpledatalogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session state machine. */
class SessionTrackerTest {
    /** Hands out predictable session ids. */
    private var nextSessionNumber = 0

    /** The next predictable id. */
    private fun newSessionId(): String {
        nextSessionNumber += 1
        return "session-$nextSessionNumber"
    }

    @Test
    fun firstActivityStartsASession() {
        val tracker = SessionTracker()

        val changes = tracker.appBecameActive(1_000) { newSessionId() }

        assertEquals(listOf(SessionTracker.Change.Started("session-1", 1_000)), changes)
        assertEquals("session-1", tracker.session?.id)
    }

    @Test
    fun shortBackgroundResumesTheSameSession() {
        val tracker = SessionTracker()
        tracker.appBecameActive(0) { newSessionId() }
        tracker.appWentToBackground(60_000)

        val changes = tracker.appBecameActive(60_000 + SessionTracker.TIMEOUT_MILLIS - 1) { newSessionId() }

        assertTrue(changes.isEmpty())
        assertEquals("session-1", tracker.session?.id)
        assertEquals(false, tracker.session?.isInBackground)
    }

    @Test
    fun thirtyMinutesAwayEndsTheSessionAtItsLastActivityAndStartsANewOne() {
        val tracker = SessionTracker()
        tracker.appBecameActive(0) { newSessionId() }
        tracker.appWentToBackground(90_400)

        val returnMillis = 90_400 + SessionTracker.TIMEOUT_MILLIS
        val changes = tracker.appBecameActive(returnMillis) { newSessionId() }

        assertEquals(
            listOf(
                SessionTracker.Change.Ended("session-1", 90_400, 90),
                SessionTracker.Change.Started("session-2", returnMillis),
            ),
            changes,
        )
    }

    @Test
    fun backgroundWithoutASessionDoesNothing() {
        val tracker = SessionTracker()
        tracker.appWentToBackground(5)

        assertNull(tracker.session)
    }

    @Test
    fun sessionsRoundTripThroughTheirFile() {
        val session = SessionTracker.Session("abc", 1, 2, true)

        assertEquals(session, SessionTracker.Session.fromJsonOrNull(session.toJson()))
        assertNull(SessionTracker.Session.fromJsonOrNull("{\"id\":\"abc\""))
        assertNull(SessionTracker.Session.fromJsonOrNull("{}"))
    }
}
