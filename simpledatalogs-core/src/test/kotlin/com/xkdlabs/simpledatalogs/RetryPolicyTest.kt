package com.xkdlabs.simpledatalogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Backoff bounds, Retry-After as a floor, and Retry-After parsing. */
class RetryPolicyTest {
    /** The default policy. */
    private val retryPolicy = RetryPolicy()

    @Test
    fun ceilingDoublesFromOneSecondUpToFiveMinutes() {
        val ceilings = (1..10).map { failureCount -> retryPolicy.backoffCeilingMillis(failureCount) }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L, 300_000L), ceilings)
        assertEquals(300_000L, retryPolicy.backoffCeilingMillis(Int.MAX_VALUE))
        assertEquals(1_000L, retryPolicy.backoffCeilingMillis(0))
    }

    @Test
    fun fullJitterStaysBetweenTheFloorAndTheCeiling() {
        assertEquals(250L, retryPolicy.delayMillis(1, null, 0.0))
        assertEquals(500L, retryPolicy.delayMillis(2, null, 0.25))
        assertEquals(300_000L, retryPolicy.delayMillis(40, null, 1.0))
        assertEquals(300_000L, retryPolicy.delayMillis(40, null, 7.0))
        assertEquals(250L, retryPolicy.delayMillis(40, null, -1.0))
    }

    @Test
    fun retryAfterIsAFloorCappedAtOneHour() {
        assertEquals(120_000L, retryPolicy.delayMillis(1, 120_000L, 0.5))
        assertEquals(3_600_000L, retryPolicy.delayMillis(1, 86_400_000L, 0.5))
        assertEquals(64_000L, retryPolicy.delayMillis(7, 1_000L, 1.0))
    }

    @Test
    fun invalidKeyProbesWaitOneMinuteThenTenAndHonorRetryAfter() {
        assertEquals(60_000L, retryPolicy.invalidKeyProbeDelayMillis(0, null))
        assertEquals(600_000L, retryPolicy.invalidKeyProbeDelayMillis(1, null))
        assertEquals(900_000L, retryPolicy.invalidKeyProbeDelayMillis(1, 900_000L))
    }

    @Test
    fun parsesDelaySeconds() {
        assertEquals(120_000L, RetryAfterHeader.parseDelayMillis("120", 0))
        assertEquals(0L, RetryAfterHeader.parseDelayMillis(" 0 ", 0))
        assertEquals(Long.MAX_VALUE / 1_000 * 1_000, RetryAfterHeader.parseDelayMillis("99999999999999999999999", 0))
    }

    @Test
    fun parsesAllThreeHttpDateLayouts() {
        // 1994-11-06T08:49:37Z, the RFC 9110 example.
        val dateMillis = 784_111_777_000L
        val nowMillis = dateMillis - 30_000

        assertEquals(30_000L, RetryAfterHeader.parseDelayMillis("Sun, 06 Nov 1994 08:49:37 GMT", nowMillis))
        assertEquals(30_000L, RetryAfterHeader.parseDelayMillis("Sunday, 06-Nov-94 08:49:37 GMT", nowMillis))
        assertEquals(30_000L, RetryAfterHeader.parseDelayMillis("Sun Nov  6 08:49:37 1994", nowMillis))
    }

    @Test
    fun pastDatesMeanNowAndGarbageMeansNoHint() {
        assertEquals(0L, RetryAfterHeader.parseDelayMillis("Sun, 06 Nov 1994 08:49:37 GMT", 2_000_000_000_000L))
        assertNull(RetryAfterHeader.parseDelayMillis("soon", 0))
        assertNull(RetryAfterHeader.parseDelayMillis("-5", 0))
        assertNull(RetryAfterHeader.parseDelayMillis("1.5", 0))
        assertNull(RetryAfterHeader.parseDelayMillis("", 0))
        assertNull(RetryAfterHeader.parseDelayMillis(null, 0))
        assertNull(RetryAfterHeader.parseDelayMillis("Sun, 06 Nov 1994 08:49:37 GMT trailing", 0))
    }

    @Test
    fun classifiesEveryStatus() {
        assertEquals(IngestOutcome.Accepted, IngestOutcome.fromResponse(IngestHttpResponse(202, null), 0))
        assertEquals(IngestOutcome.InvalidKey, IngestOutcome.fromResponse(IngestHttpResponse(401, null), 0))
        assertEquals(IngestOutcome.PayloadTooLarge, IngestOutcome.fromResponse(IngestHttpResponse(413, null), 0))
        assertEquals(IngestOutcome.Unsupported, IngestOutcome.fromResponse(IngestHttpResponse(415, null), 0))
        assertEquals(IngestOutcome.Retryable(5_000L, "HTTP 503"), IngestOutcome.fromResponse(IngestHttpResponse(503, "5"), 0))

        // Other 2xx answers were not stored, so they are retried too.
        for (statusCode in listOf(200, 204, 301, 400, 429, 500)) {
            assertEquals(IngestOutcome.Retryable(null, "HTTP $statusCode"), IngestOutcome.fromResponse(IngestHttpResponse(statusCode, null), 0))
        }
    }
}
