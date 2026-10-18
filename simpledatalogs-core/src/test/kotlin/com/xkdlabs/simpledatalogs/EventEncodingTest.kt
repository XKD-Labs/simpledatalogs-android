package com.xkdlabs.simpledatalogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire line: field names, escapes, property types, default merging and the ts format. */
class EventEncodingTest {
    /** Builds an event with fixed ids and time. */
    private fun makeEvent(properties: Map<String, Any>, deviceId: String? = null, sessionId: String? = null): TrackedEvent {
        return TrackedEvent("level_complete", "8a1f6c1e-3c1b-4b8a-9f2e-0c6d5e4f3a2b", 1_760_260_443_120L, deviceId, sessionId, properties)
    }

    @Test
    fun encodesTheWireFieldsInOrderAndLeavesOutUnsetIds() {
        val eventLine = makeEvent(mapOf("level" to 3L)).encodedLine()

        assertEquals(
            """{"event":"level_complete","event_id":"8a1f6c1e-3c1b-4b8a-9f2e-0c6d5e4f3a2b","ts":"2025-10-12T09:14:03.120Z","props":{"level":3}}""",
            eventLine,
        )
    }

    @Test
    fun includesDeviceAndSessionIdsWhenSet() {
        val parsedEvent = JsonReader.parse(makeEvent(emptyMap(), "device-1", "session-1").encodedLine()) as Map<*, *>

        assertEquals("device-1", parsedEvent["device_id"])
        assertEquals("session-1", parsedEvent["session_id"])
        assertEquals(emptyMap<String, Any>(), parsedEvent["props"])
    }

    @Test
    fun escapesQuotesBackslashesNewlinesAndControlCharacters() {
        val trickyText = "say \"hi\"\\ now\nnext\r\ttab\u0001\u001f  é 🎉"
        val eventLine = makeEvent(mapOf("text" to trickyText)).encodedLine()

        // One event is always one line, and it parses back to the same text.
        assertFalse(eventLine.contains('\n'))
        assertFalse(eventLine.contains('\r'))
        assertTrue(eventLine.contains("\\u0001"))
        assertTrue(eventLine.contains("\\u001f"))
        assertTrue(eventLine.contains("\\\"hi\\\""))

        val parsedProps = (JsonReader.parse(eventLine) as Map<*, *>)["props"] as Map<*, *>
        assertEquals(trickyText, parsedProps["text"])
    }

    @Test
    fun keepsStringsNumbersAndBooleansAndDropsEverythingElse() {
        val logger = RecordingLogger()
        val sanitized = Properties.sanitize(
            linkedMapOf(
                "name" to "pro",
                "count" to 3,
                "big" to 9_000_000_000L,
                "short" to 7.toShort(),
                "ratio" to 0.25,
                "float" to 0.1f,
                "is_pro" to true,
                "nan" to Double.NaN,
                "infinite" to Double.POSITIVE_INFINITY,
                "float_nan" to Float.NaN,
                "missing" to null,
                "list" to listOf(1, 2),
                "map" to mapOf("a" to 1),
                "object" to Any(),
            ),
            logger,
        )

        assertEquals(linkedMapOf<String, Any>("name" to "pro", "count" to 3L, "big" to 9_000_000_000L, "short" to 7L, "ratio" to 0.25, "float" to 0.1, "is_pro" to true), sanitized)

        // Each dropped key gets its own log line.
        for (droppedKey in listOf("nan", "infinite", "float_nan", "missing", "list", "map", "object")) {
            assertTrue("no log for $droppedKey", logger.contains("Dropped property $droppedKey"))
        }
    }

    @Test
    fun encodesNumbersAsJsonNumbers() {
        val eventLine = makeEvent(sortedMapOf("ratio" to 0.1, "huge" to 1.0e21, "negative" to -5L)).encodedLine()
        val parsedProps = (JsonReader.parse(eventLine) as Map<*, *>)["props"] as Map<*, *>

        assertEquals(0.1, parsedProps["ratio"])
        assertEquals(1.0e21, parsedProps["huge"])
        assertEquals(-5L, parsedProps["negative"])
    }

    @Test
    fun mergesDefaultsUnderTheEventsOwnProperties() {
        val merged = Properties.merge(mapOf("app_version" to "1.1.0", "plan" to "free"), mapOf("plan" to "pro", "level" to 3L))

        assertEquals(mapOf("app_version" to "1.1.0", "level" to 3L, "plan" to "pro"), merged)
        assertEquals(listOf("app_version", "level", "plan"), merged.keys.toList())
    }

    @Test
    fun formatsTimestampsAsUtcIso8601WithMilliseconds() {
        assertEquals("1970-01-01T00:00:00.000Z", Timestamps.formatIso8601Utc(0))
        assertEquals("2025-10-12T09:14:03.120Z", Timestamps.formatIso8601Utc(1_760_260_443_120L))
        assertEquals("2024-02-29T23:59:59.999Z", Timestamps.formatIso8601Utc(1_709_251_199_999L))
        assertEquals("2000-03-01T00:00:00.001Z", Timestamps.formatIso8601Utc(951_868_800_001L))
        assertEquals("1969-12-31T23:59:59.999Z", Timestamps.formatIso8601Utc(-1))
    }

    @Test
    fun formatsTimestampsLikeTheJdkForManyRandomInstants() {
        val referenceFormatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        referenceFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val random = java.util.Random(42)

        repeat(2_000) {
            val epochMillis = (random.nextDouble() * 6_000_000_000_000L).toLong()
            assertEquals(referenceFormatter.format(java.util.Date(epochMillis)), Timestamps.formatIso8601Utc(epochMillis))
        }
    }
}
