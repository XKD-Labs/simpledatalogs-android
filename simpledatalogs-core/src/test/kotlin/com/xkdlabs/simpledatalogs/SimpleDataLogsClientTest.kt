package com.xkdlabs.simpledatalogs

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The client's public behaviour with a scripted transport. */
class SimpleDataLogsClientTest {
    /** A fresh outbox folder per test. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun aFullBatchGoesOutWithoutWaitingForTheTimer() {
        val transport = ScriptedTransport()
        val client = makeTestClient(temporaryFolder.root, transport, maxBatchEventCount = 5)

        repeat(5) { eventNumber -> client.track("event_$eventNumber") }
        client.settle()

        assertEquals(listOf(5), transport.requests.map { request -> request.events.size })
        client.shutdown(0)
    }

    @Test
    fun aPartialBatchGoesOutWhenTheBatchTimerFires() {
        val transport = ScriptedTransport()
        val timers = ManualTimerScheduler()
        val client = makeTestClient(temporaryFolder.root, transport, timers)

        client.track("lonely")
        client.settle()
        assertEquals(0, transport.requests.size)
        assertEquals(listOf(10_000L), timers.pendingDelays())

        timers.fireWithDelay(10_000)
        client.settle()

        assertEquals(1, transport.requests.size)
        client.shutdown(0)
    }

    @Test
    fun defaultsDeviceIdAndScreenViewsReachTheWire() {
        val transport = ScriptedTransport()
        val client = makeTestClient(temporaryFolder.root, transport)

        client.setDeviceId("device-1")
        client.setDefaultProperties(mapOf("app_version" to "1.1.0", "plan" to "free"))
        client.trackScreen("Settings", mapOf("plan" to "pro"))
        client.flushBlocking(5_000)

        val event = transport.requests.single().events.single()
        assertEquals("screen_view", event["event"])
        assertEquals("device-1", event["device_id"])
        assertEquals(mapOf("app_version" to "1.1.0", "plan" to "pro", "screen" to "Settings"), event["props"])
        assertEquals("simpledatalogs-kotlin/0.1.0", transport.requests.single().headers["user-agent"])
        client.shutdown(0)
    }

    @Test
    fun anEmptyEventNameIsDroppedWithALogLine() {
        val transport = ScriptedTransport()
        val logger = RecordingLogger()
        val client = makeTestClient(temporaryFolder.root, transport, logger = logger)

        client.track("")

        assertTrue(client.flushBlocking(5_000))
        assertEquals(0, transport.requests.size)
        assertTrue(logger.contains("empty event name"))
        client.shutdown(0)
    }

    @Test
    fun leftoversOfAnEarlierRunAreSentOnTheNextStart() {
        val failingTransport = ScriptedTransport(fallbackStatus = 503)
        val firstRun = makeTestClient(temporaryFolder.root, failingTransport)
        firstRun.track("kept_one")
        firstRun.track("kept_two")
        firstRun.shutdown(1_000)

        val acceptingTransport = ScriptedTransport()
        val secondRun = makeTestClient(temporaryFolder.root, acceptingTransport)
        secondRun.settle()

        assertEquals(failingTransport.requests.last().eventIds, acceptingTransport.requests.single().eventIds)
        assertEquals(0, secondRun.pendingEvents())
        secondRun.shutdown(0)
    }

    @Test
    fun startingOptedOutDeletesLeftoversWithoutSendingThem() {
        val failingTransport = ScriptedTransport(fallbackStatus = 503)
        val firstRun = makeTestClient(temporaryFolder.root, failingTransport)
        firstRun.track("private")
        firstRun.shutdown(1_000)

        val secondTransport = ScriptedTransport()
        val secondRun = makeTestClient(temporaryFolder.root, secondTransport, isInitiallyEnabled = false)
        secondRun.track("ignored")
        secondRun.settle()

        assertEquals(0, secondTransport.requests.size)
        assertEquals(0, secondRun.pendingEvents())
        assertEquals(0, File(temporaryFolder.root, "batches").listFiles()!!.size)
        secondRun.shutdown(0)
    }

    @Test
    fun optingOutDeletesUnsentEventsAndOptingInCollectsAgain() {
        val transport = ScriptedTransport(fallbackStatus = 503)
        val client = makeTestClient(temporaryFolder.root, transport)
        client.track("before_opt_out")
        client.flushBlocking(1_000)

        client.setEnabled(false)
        client.track("while_opted_out")
        client.settle()
        assertEquals(0, client.pendingEvents())

        client.setEnabled(true)
        client.track("after_opt_in")
        client.settle()
        assertEquals(1, client.pendingEvents())
        client.shutdown(0)
    }

    @Test
    fun tracksBeyondThePendingCapAreSkippedWithOneLogLine() {
        val logger = RecordingLogger()
        val configuration = ClientConfiguration(ingestKey = "sdl_pub_test", storageDirectory = temporaryFolder.root, endpoint = "http://127.0.0.1:9", maxPendingTrackCount = 3)
        val client = SimpleDataLogsClient(configuration, ClientDependencies(transport = ScriptedTransport(), timerScheduler = ManualTimerScheduler(), logger = logger))

        // Hold the worker, so tracks pile up.
        val releaseWorker = CountDownLatch(1)
        client.executeInBackground { releaseWorker.await(5, TimeUnit.SECONDS) }
        repeat(10) { eventNumber -> client.track("event_$eventNumber") }
        releaseWorker.countDown()
        client.settle()

        assertEquals(3, client.pendingEvents())
        assertEquals(1, logger.lines.count { line -> line.contains("wait for the disk") })
        client.shutdown(0)
    }

    @Test
    fun aClosedClientDropsTracksAndFlushBlockingReportsFalse() {
        val logger = RecordingLogger()
        val client = makeTestClient(temporaryFolder.root, ScriptedTransport(), logger = logger)
        client.close()

        client.track("too_late")

        assertTrue(logger.contains("after the client was closed"))
        assertFalse(client.flushBlocking(100))
    }

    @Test
    fun theIngestUrlTrimsOneTrailingSlash() {
        assertEquals("https://edge.simpledatalogs.com/v1/ingest", ClientConfiguration("k", temporaryFolder.root).ingestUrl)
        assertEquals("http://localhost:4001/v1/ingest", ClientConfiguration("k", temporaryFolder.root, endpoint = "http://localhost:4001/").ingestUrl)
    }
}
