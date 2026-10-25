package com.xkdlabs.simpledatalogs

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Activity stamps: app_state and engaged_ms on events tracked now, from the app's foreground state. */
class ActivityTrackerTest {
    /** A fresh outbox folder per test. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun foregroundTimeCountsBetweenStampsAndBackgroundTimeIsLeftOut() {
        val tracker = ActivityTracker(isActive = true, atMillis = 0)

        assertEquals(mapOf("app_state" to "active", "engaged_ms" to 30_000L), tracker.stamp(30_000))

        tracker.setActive(false, 40_000)
        assertEquals(mapOf("app_state" to "background"), tracker.stamp(600_000))

        // Back in front for 20 seconds after an hour away; a repeated notification changes nothing.
        tracker.setActive(true, 3_600_000)
        tracker.setActive(true, 3_610_000)
        assertEquals(mapOf("app_state" to "active", "engaged_ms" to 20_000L), tracker.stamp(3_620_000))
    }

    @Test
    fun aFinishedStretchCountsOnceAndTheEngagedTimeNeverPassesADay() {
        val tracker = ActivityTracker(isActive = false, atMillis = 0)

        tracker.setActive(true, 100)
        tracker.setActive(false, 145)
        tracker.setActive(true, 200)
        assertEquals(mapOf("app_state" to "active", "engaged_ms" to 55L), tracker.stamp(210))
        assertEquals(mapOf("app_state" to "active", "engaged_ms" to 86_400_000L), tracker.stamp(2 * 86_400_000L))
    }

    @Test
    fun eventsTrackedNowCarryTheStampAndTheCallersOwnValueWins() {
        val transport = ScriptedTransport()
        val clock = ManualClock()
        val client = makeTestClient(temporaryFolder.root, transport, clock = clock)

        // Without a reported state, nothing is stamped.
        client.track("before_lifecycle")

        client.setAppActive(true)
        clock.advance(5_000)
        client.track("opened")

        client.setAppActive(false)
        clock.advance(60_000)
        client.track("synced")
        client.track("marked", mapOf("app_state" to "active"))
        client.trackAt("session_end", emptyMap(), clock.currentTimeMillis(), null)
        client.flushBlocking(5_000)

        val sentProps = transport.requests.flatMap { request -> request.events }.map { event -> event["props"] ?: emptyMap<String, Any>() }
        assertEquals(
            listOf(
                emptyMap<String, Any>(),
                mapOf("app_state" to "active", "engaged_ms" to 5_000L),
                mapOf("app_state" to "background"),
                mapOf("app_state" to "active"),
                emptyMap<String, Any>(),
            ),
            sentProps,
        )
        client.shutdown(0)
    }
}
