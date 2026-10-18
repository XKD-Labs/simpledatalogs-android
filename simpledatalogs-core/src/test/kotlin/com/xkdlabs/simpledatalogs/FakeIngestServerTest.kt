package com.xkdlabs.simpledatalogs

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The client against a real local HTTP server that answers scripted statuses. */
class FakeIngestServerTest {
    /** A fresh outbox folder per test. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** The local ingest server. */
    private lateinit var server: HttpServer

    /** Statuses (and Retry-After values) still to answer; 202 once empty. */
    private val scriptedAnswers = ArrayDeque<Pair<Int, String?>>()

    /** Every request the server received. */
    private val receivedRequests: MutableList<ReceivedRequest> = java.util.Collections.synchronizedList(ArrayList())

    /** Fires timers on demand. */
    private val timers = ManualTimerScheduler()

    /** A clock the tests move by hand. */
    private val clock = ManualClock()

    /** Collects log lines. */
    private val logger = RecordingLogger()

    /** The client under test. */
    private lateinit var client: SimpleDataLogsClient

    @Before
    fun startServerAndClient() {
        // A real HTTP server on a free local port, serving only /v1/ingest.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/ingest") { exchange ->
            val rawBody = exchange.requestBody.readBytes()
            val headers = exchange.requestHeaders.entries.associate { (headerName, headerValues) -> headerName.lowercase() to headerValues.first() }
            receivedRequests.add(ReceivedRequest(headers, decodeBody(rawBody, headers["content-encoding"]), rawBody))

            // Answer the next scripted status.
            val (statusCode, retryAfterHeader) = synchronized(scriptedAnswers) { scriptedAnswers.removeFirstOrNull() } ?: Pair(202, null)
            if (retryAfterHeader != null) {
                exchange.responseHeaders.add("Retry-After", retryAfterHeader)
            }

            exchange.sendResponseHeaders(statusCode, -1)
            exchange.close()
        }
        server.start()

        // The endpoint has a trailing slash, which the client trims.
        client = makeTestClient(
            storageDirectory = temporaryFolder.root,
            transport = HttpUrlConnectionTransport(5_000),
            timerScheduler = timers,
            clock = clock,
            logger = logger,
            endpoint = "http://127.0.0.1:${server.address.port}/",
        )
    }

    @After
    fun stopServerAndClient() {
        client.shutdown(0)
        server.stop(0)
    }

    /** Queues the server's next answers. */
    private fun answer(vararg answers: Pair<Int, String?>) {
        synchronized(scriptedAnswers) {
            scriptedAnswers.addAll(answers)
        }
    }

    @Test
    fun acceptedBatchIsDeletedAndCarriesEveryHeader() {
        client.track("level_complete", mapOf("level" to 3))
        client.track("purchase", mapOf("plan" to "pro"))

        assertTrue(client.flushBlocking(5_000))

        val request = receivedRequests.single()
        assertEquals("Bearer sdl_pub_test", request.headers["authorization"])
        assertEquals("application/x-ndjson", request.headers["content-type"])
        assertEquals("simpledatalogs-kotlin/0.1.0", request.headers["user-agent"])
        assertNull(request.headers["content-encoding"])
        assertEquals(listOf("level_complete", "purchase"), request.events.map { event -> event["event"] })
        assertEquals("2025-10-12T09:14:03.120Z", request.events[0]["ts"])
        assertEquals(0, client.pendingEvents())
    }

    @Test
    fun largeBodiesAreGzipped() {
        repeat(30) { eventNumber -> client.track("page_view", mapOf("path" to "/docs/getting-started/$eventNumber", "referrer" to "https://example.com")) }

        assertTrue(client.flushBlocking(5_000))

        val request = receivedRequests.single()
        assertEquals("gzip", request.headers["content-encoding"])
        assertEquals(30, request.events.size)
        assertTrue(request.rawBody.size < request.decodedBody.length)
    }

    @Test
    fun serviceUnavailableWaitsForRetryAfterAndResendsTheSameEvent() {
        answer(503 to "120")
        client.track("level_complete")

        // The first attempt fails, so the event stays and a retry waits at least Retry-After.
        assertFalse(client.flushBlocking(5_000))
        assertEquals(listOf(120_000L), timers.pendingDelays())
        assertEquals(1, client.pendingEvents())

        // An hour later the retry sends the same event_id and ts.
        clock.advance(3_600_000)
        timers.fireWithDelay(120_000)
        client.settle()

        assertEquals(2, receivedRequests.size)
        assertEquals(receivedRequests[0].events, receivedRequests[1].events)
        assertEquals(0, client.pendingEvents())
    }

    @Test
    fun invalidKeyPausesThenProbesWithTheSmallestBatchUntilAccepted() {
        answer(401 to null)
        client.track("first")
        assertFalse(client.flushBlocking(5_000))

        // Paused: new events are kept, not sent, and the first probe is a minute out.
        client.track("second")
        client.track("third")
        assertFalse(client.flushBlocking(5_000))
        assertEquals(1, receivedRequests.size)
        assertEquals(listOf(60_000L), timers.pendingDelays())

        // The first probe is rejected again, so the next one waits ten minutes.
        answer(401 to null)
        timers.fireWithDelay(60_000)
        client.settle()
        assertEquals(2, receivedRequests.size)
        assertEquals(1, receivedRequests[1].events.size)
        assertEquals(listOf(600_000L), timers.pendingDelays())

        // The key goes live: the probe gets a 202 and everything else follows at once.
        timers.fireWithDelay(600_000)
        client.settle()
        assertEquals(4, receivedRequests.size)
        assertEquals(setOf("first", "second", "third"), receivedRequests.drop(2).flatMap { request -> request.events }.map { event -> event["event"] }.toSet())
        assertEquals(0, client.pendingEvents())
        assertEquals(1, logger.lines.count { line -> line.contains("rejected the key (401)") })
    }

    @Test
    fun probeLostToServiceUnavailableKeepsThePauseAndHonorsRetryAfter() {
        answer(401 to null, 503 to "900")
        client.track("first")
        client.flushBlocking(5_000)

        timers.fireWithDelay(60_000)
        client.settle()

        assertEquals(listOf(900_000L), timers.pendingDelays())
        assertEquals(1, client.pendingEvents())
    }

    @Test
    fun payloadTooLargeSplitsTheBatchInHalf() {
        answer(413 to null)
        repeat(4) { eventNumber -> client.track("event_$eventNumber") }

        assertTrue(client.flushBlocking(5_000))

        assertEquals(listOf(4, 2, 2), receivedRequests.map { request -> request.events.size })
        assertEquals(receivedRequests[0].eventIds, receivedRequests[1].eventIds + receivedRequests[2].eventIds)
    }

    @Test
    fun unsupportedBatchIsSetAsideAndTheNextOneGoes() {
        answer(503 to null)
        client.track("refused")
        client.flushBlocking(5_000)
        client.track("accepted")

        // The retry sends both batches: the first is refused for good, the second accepted.
        answer(415 to null)
        timers.fireAll()
        client.settle()

        assertEquals(listOf("refused", "refused", "accepted"), receivedRequests.map { request -> request.events.single()["event"] })
        assertEquals(1, File(temporaryFolder.root, "batches").listFiles()!!.count { file -> file.name.endsWith(".unsupported") })
        assertEquals(0, client.pendingEvents())
        assertTrue(logger.contains("unsupported (415)"))
    }

    @Test
    fun batchThatFailsToReadThreeTimesIsSetAside() {
        answer(503 to null)
        client.track("doomed")
        client.flushBlocking(5_000)

        // Replace the batch file with a folder of the same name, so every read fails.
        val batchFile = client.sealedBatches().single().file
        assertTrue(batchFile.delete())
        assertTrue(batchFile.mkdir())

        repeat(3) {
            timers.fireAll()
            client.settle()
        }

        assertTrue(File(batchFile.path + ".unreadable").exists())
        assertEquals(0, client.pendingEvents())
        assertEquals(1, receivedRequests.size)
    }
}
