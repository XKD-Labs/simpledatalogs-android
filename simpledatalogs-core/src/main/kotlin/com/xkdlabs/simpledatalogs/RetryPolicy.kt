package com.xkdlabs.simpledatalogs

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Exponential backoff with full jitter, from 1 s up to 5 min, that never retries sooner than Retry-After. */
@InternalSimpleDataLogsApi
public class RetryPolicy(
    /** The backoff ceiling after the first failure. */
    public val initialDelayMillis: Long = 1_000,

    /** The largest backoff ceiling. */
    public val maximumDelayMillis: Long = 300_000,

    /** The shortest wait, so a jitter draw near zero can never spin the send loop. */
    public val minimumDelayMillis: Long = 250,

    /** The longest Retry-After honored, so a broken header cannot stall sending for days. */
    public val maximumRetryAfterMillis: Long = 3_600_000,

    /** The first re-probe after a 401, soon enough to catch a brand-new key going live. */
    public val invalidKeyFirstProbeDelayMillis: Long = 60_000,

    /** Every later re-probe while the key keeps answering 401. */
    public val invalidKeyProbeIntervalMillis: Long = 600_000,
) {
    /** The jitter-free ceiling after this many consecutive failures: 1 s, 2 s, 4 s … 5 min. */
    public fun backoffCeilingMillis(failureCount: Int): Long {
        // Cap the exponent so the doubling can never overflow.
        val doublingCount = (failureCount - 1).coerceIn(0, 30)

        return minOf(maximumDelayMillis, initialDelayMillis shl doublingCount)
    }

    /** The wait before the next attempt: a full-jitter backoff of at least 250 ms, but never less than Retry-After. */
    public fun delayMillis(failureCount: Int, retryAfterMillis: Long?, jitterFraction: Double): Long {
        // Full jitter picks uniformly between zero and the ceiling, then the floor applies.
        val clampedJitterFraction = jitterFraction.coerceIn(0.0, 1.0)
        val jitteredBackoffMillis = maxOf(minimumDelayMillis, (backoffCeilingMillis(failureCount) * clampedJitterFraction).toLong())

        return honoringRetryAfter(retryAfterMillis, jitteredBackoffMillis)
    }

    /** The wait before the next 401 re-probe, pushed later by a Retry-After from a probe that got 429 or 503. */
    public fun invalidKeyProbeDelayMillis(probeCount: Int, retryAfterMillis: Long?): Long {
        val scheduledProbeDelayMillis = if (probeCount == 0) invalidKeyFirstProbeDelayMillis else invalidKeyProbeIntervalMillis

        return honoringRetryAfter(retryAfterMillis, scheduledProbeDelayMillis)
    }

    // Treats the server's Retry-After as a floor over the delay, within a sane bound.
    private fun honoringRetryAfter(retryAfterMillis: Long?, delayMillis: Long): Long {
        if (retryAfterMillis == null) {
            return delayMillis
        }

        return maxOf(delayMillis, minOf(retryAfterMillis, maximumRetryAfterMillis))
    }
}

/** Parses an HTTP Retry-After header, given as delay-seconds or an HTTP-date (RFC 9110). */
@InternalSimpleDataLogsApi
public object RetryAfterHeader {
    /** The HTTP-date layouts recipients accept: IMF-fixdate, RFC 850 and asctime. */
    private val httpDateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )

    /** The longest delay-seconds value kept before it is clamped, so the millisecond math never overflows. */
    private const val MAX_DELAY_SECONDS = Long.MAX_VALUE / 1_000

    /** Milliseconds to wait from `nowMillis`, or null when the header is missing or unreadable. */
    public fun parseDelayMillis(headerValue: String?, nowMillis: Long): Long? {
        // A missing or blank header carries no hint.
        val trimmedValue = headerValue?.trim().orEmpty()
        if (trimmedValue.isEmpty()) {
            return null
        }

        // delay-seconds is a bare non-negative integer; a huge one is clamped.
        if (trimmedValue.all { character -> character in '0'..'9' }) {
            val delaySeconds = trimmedValue.toLongOrNull() ?: MAX_DELAY_SECONDS

            return minOf(delaySeconds, MAX_DELAY_SECONDS) * 1_000
        }

        // An HTTP-date in the past means retry now.
        val retryAtMillis = parseHttpDateMillis(trimmedValue) ?: return null

        return maxOf(0L, retryAtMillis - nowMillis)
    }

    // Parses an HTTP-date in any of the three accepted layouts.
    private fun parseHttpDateMillis(dateText: String): Long? {
        // asctime pads single-digit days with a second space, so collapse runs of spaces first.
        val normalizedDateText = dateText.split(' ').filter { part -> part.isNotEmpty() }.joinToString(" ")

        // Try each layout in turn; HTTP-dates are always English and always GMT.
        for (httpDateFormat in httpDateFormats) {
            val dateFormatter = SimpleDateFormat(httpDateFormat, Locale.US)
            dateFormatter.timeZone = TimeZone.getTimeZone("GMT")
            dateFormatter.isLenient = false

            // Only a parse that consumes the whole text counts.
            val parsePosition = ParsePosition(0)
            val parsedDate = dateFormatter.parse(normalizedDateText, parsePosition)
            if (parsedDate != null && parsePosition.index == normalizedDateText.length) {
                return parsedDate.time
            }
        }

        return null
    }
}
