package com.xkdlabs.simpledatalogs

import java.io.IOException
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Randomized checks: the encoder always writes one valid line, and random server answers never lose or double an event. */
class PropertyStyleTest {
    /** A fresh outbox folder per run. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** A random string mixing ASCII, control characters, quotes, backslashes, BMP text and emoji. */
    private fun randomText(random: Random): String {
        val textBuilder = StringBuilder()

        repeat(random.nextInt(40)) {
            when (random.nextInt(6)) {
                0 -> textBuilder.append(random.nextInt(0x20).toChar())
                1 -> textBuilder.append("\"\\/\n\r\t"[random.nextInt(6)])
                2 -> textBuilder.append((0x20 + random.nextInt(0x5F)).toChar())
                3 -> textBuilder.append((0xA0 + random.nextInt(0xD000)).toChar())
                4 -> textBuilder.appendCodePoint(0x1F300 + random.nextInt(0x300))
                else -> textBuilder.append(' ')
            }
        }

        return textBuilder.toString()
    }

    /** A random property value of any supported type. */
    private fun randomValue(random: Random): Any {
        return when (random.nextInt(5)) {
            0 -> randomText(random)
            1 -> random.nextLong()
            2 -> random.nextInt()
            3 -> (random.nextDouble() - 0.5) * Math.pow(10.0, random.nextInt(40).toDouble() - 20)
            else -> random.nextBoolean()
        }
    }

    @Test
    fun randomPropertiesRoundTripAsOneValidJsonLine() {
        val random = Random(7)

        repeat(2_000) {
            // Build a random event.
            val properties = (0 until random.nextInt(8)).associate { randomText(random) to randomValue(random) }
            val sanitized = Properties.sanitize(properties, RecordingLogger())
            val event = TrackedEvent(randomText(random), "id", random.nextLong() and 0xFFFFFFFFFFFL, randomText(random), null, sanitized)

            // It is one line, and it parses back to exactly what went in.
            val eventLine = event.encodedLine()
            assertFalse(eventLine.contains('\n'))
            assertFalse(eventLine.contains('\r'))

            val parsedEvent = JsonReader.parse(eventLine) as Map<*, *>
            assertEquals(event.eventName, parsedEvent["event"])
            assertEquals(event.deviceId, parsedEvent["device_id"])
            assertEquals(sanitized, parsedEvent["props"])
        }
    }

    /** A transport that answers at random and keeps score of what ingest stored. */
    private class RandomIngest(private val random: Random) : IngestTransport {
        /** How many times each event id was stored. */
        val storedCounts = HashMap<String, Int>()

        /** Ids stored by a request whose answer never arrived, so a resend may store them again. */
        val idsWithUnknownOutcome = HashSet<String>()

        /** Ids dropped because they alone were too large. */
        val idsDroppedAsTooLarge = HashSet<String>()

        /** When true, every request is accepted. */
        @Volatile
        var isAlwaysAccepting = false

        /** Answers one request at random. */
        @Synchronized
        override fun post(url: String, headers: Map<String, String>, body: ByteArray): IngestHttpResponse {
            val eventIds = ReceivedRequest(emptyMap(), decodeBody(body, headers["Content-Encoding"]), body).eventIds
            val answerRoll = if (isAlwaysAccepting) 0 else random.nextInt(9)

            return when (answerRoll) {
                0, 1 -> {
                    eventIds.forEach { eventId -> storedCounts[eventId] = (storedCounts[eventId] ?: 0) + 1 }
                    IngestHttpResponse(202, null)
                }
                2 -> IngestHttpResponse(503, if (random.nextBoolean()) "3" else null)
                3 -> IngestHttpResponse(429, null)
                4 -> IngestHttpResponse(500, null)
                5 -> IngestHttpResponse(401, null)
                6 -> {
                    if (eventIds.size == 1) {
                        idsDroppedAsTooLarge.add(eventIds.single())
                    }

                    IngestHttpResponse(413, null)
                }
                7 -> throw IOException("connection reset before sending")
                else -> {
                    // Stored, but the answer was lost on the way back.
                    eventIds.forEach { eventId -> storedCounts[eventId] = (storedCounts[eventId] ?: 0) + 1 }
                    idsWithUnknownOutcome.addAll(eventIds)
                    throw IOException("timeout after the server stored the batch")
                }
            }
        }
    }

    @Test
    fun randomServerAnswersNeverLoseOrDoubleAnEvent() {
        repeat(25) { runNumber ->
            val random = Random(1_000L + runNumber)
            val transport = RandomIngest(random)
            val timers = ManualTimerScheduler()
            val storageDirectory = temporaryFolder.newFolder("run-$runNumber")
            val client = makeTestClient(storageDirectory, transport, timers, maxBatchEventCount = 1 + random.nextInt(8))
            val trackedNames = ArrayList<String>()

            // Random rounds of tracking, flushing and firing timers.
            repeat(12) { roundNumber ->
                repeat(random.nextInt(15)) { eventNumber ->
                    val eventName = "run${runNumber}_round${roundNumber}_event$eventNumber"
                    trackedNames.add(eventName)
                    client.track(eventName, mapOf("n" to eventNumber))
                }

                if (random.nextBoolean()) {
                    client.flush()
                }

                client.settle()
                if (random.nextBoolean()) {
                    timers.fireAll()
                    client.settle()
                }
            }

            // Then the server heals, and every timer runs until the outbox is empty.
            transport.isAlwaysAccepting = true
            var healingRounds = 0
            while (client.pendingEvents() > 0 && healingRounds < 50) {
                client.flush()
                timers.fireAll()
                client.settle()
                healingRounds += 1
            }

            assertEquals("run $runNumber left events behind", 0, client.pendingEvents())
            client.shutdown(0)

            // Every event was stored, or dropped only as a lone too-large event.
            val storedIds = transport.storedCounts.keys
            assertEquals(trackedNames.size, storedIds.size + transport.idsDroppedAsTooLarge.size - storedIds.intersect(transport.idsDroppedAsTooLarge).size)

            // Nothing was stored twice unless an earlier answer was lost.
            for ((eventId, storedCount) in transport.storedCounts) {
                assertTrue("event $eventId stored $storedCount times", storedCount == 1 || eventId in transport.idsWithUnknownOutcome)
            }
        }
    }
}
