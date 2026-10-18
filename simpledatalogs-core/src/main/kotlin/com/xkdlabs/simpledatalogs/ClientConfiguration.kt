package com.xkdlabs.simpledatalogs

import java.io.File
import kotlin.random.Random

/** Every tunable of a client; the defaults follow the ingest API, and tests shrink them. */
@InternalSimpleDataLogsApi
public class ClientConfiguration(
    /** The bearer key sent with every request. */
    public val ingestKey: String,

    /** The outbox folder holding the journal and the sealed batches. */
    public val storageDirectory: File,

    /** The ingest host; requests go to `<endpoint>/v1/ingest`. */
    public val endpoint: String = SimpleDataLogsClient.DEFAULT_ENDPOINT,

    /** The User-Agent every request carries, e.g. `simpledatalogs-kotlin/0.1.0`. */
    public val userAgent: String = "simpledatalogs-kotlin/${SimpleDataLogsClient.SDK_VERSION}",

    /** Seal and send a partial batch this long after its first event (apps 30 s, servers 5 s). */
    public val batchIntervalMillis: Long = 30_000,

    /** Seal and send as soon as the journal holds this many events. */
    public val maxBatchEventCount: Int = 50,

    /** The largest request body ever sent, in uncompressed bytes including newlines. */
    public val maxRequestBytes: Int = 256_000,

    /** The most events the outbox keeps before it drops the oldest batches. */
    public val maxOutboxEventCount: Int = 10_000,

    /** The most bytes the outbox keeps before it drops the oldest batches. */
    public val maxOutboxBytes: Long = 10L * 1024 * 1024,

    /** The most sealed batch files the outbox keeps before it drops the oldest. */
    public val maxSealedBatchCount: Int = 500,

    /** The most track calls waiting for the disk before new ones are skipped. */
    public val maxPendingTrackCount: Int = 5_000,

    /** How long connecting, and then reading the answer, may each take. */
    public val requestTimeoutMillis: Int = 30_000,

    /** Reads of one batch that may fail in a row before it is set aside. */
    public val maxBatchReadAttempts: Int = 3,

    /** Bodies of at least this many bytes are gzipped. */
    public val gzipThresholdBytes: Int = 1_024,

    /** Backoff and Retry-After rules for failed sends. */
    public val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    /** The full ingest URL: the endpoint without one trailing slash, plus `/v1/ingest`. */
    public val ingestUrl: String
        get() = endpoint.trim().removeSuffix("/") + "/v1/ingest"

    /** The outbox limits derived from this configuration. */
    internal val outboxLimits: OutboxLimits
        get() = OutboxLimits(
            maxBatchEventCount = maxBatchEventCount,
            maxBatchBytes = maxRequestBytes,
            maxTotalEventCount = maxOutboxEventCount,
            maxTotalBytes = maxOutboxBytes,
            maxSealedBatchCount = maxSealedBatchCount,
        )
}

/** The swappable collaborators of a client: clock, jitter, transport, timers and logger. */
@InternalSimpleDataLogsApi
public class ClientDependencies(
    /** Stamps events and reads Retry-After dates. */
    public val clock: Clock = Clock.SYSTEM,

    /** Picks the full-jitter fraction in 0..1. */
    public val jitterSource: () -> Double = { Random.Default.nextDouble() },

    /** Sends requests; null means HttpURLConnection with the configured timeout. */
    public val transport: IngestTransport? = null,

    /** Runs batch, retry and probe timers; null means the client's own worker thread. */
    public val timerScheduler: TimerScheduler? = null,

    /** Where every log line goes. */
    public val logger: SdkLogger = JavaUtilSdkLogger,
)
