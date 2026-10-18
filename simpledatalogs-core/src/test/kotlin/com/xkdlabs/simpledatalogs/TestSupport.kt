package com.xkdlabs.simpledatalogs

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/** Collects every log line, so tests can assert on them. */
class RecordingLogger : SdkLogger {
    /** Every line, prefixed with its level. */
    val lines: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** Records an info line. */
    override fun info(message: String) {
        lines.add("INFO $message")
    }

    /** Records a warning line. */
    override fun warning(message: String) {
        lines.add("WARNING $message")
    }

    /** Records an error line. */
    override fun error(message: String, throwable: Throwable?) {
        lines.add("ERROR $message")
    }

    /** True when any line contains the text. */
    fun contains(text: String): Boolean {
        return synchronized(lines) { lines.any { line -> line.contains(text) } }
    }
}

/** A clock tests move by hand. */
class ManualClock(startMillis: Long = 1_760_260_443_120L) : Clock {
    /** The current time. */
    private val nowMillis = AtomicLong(startMillis)

    /** Returns the current test time. */
    override fun currentTimeMillis(): Long {
        return nowMillis.get()
    }

    /** Moves the clock forward. */
    fun advance(millis: Long) {
        nowMillis.addAndGet(millis)
    }
}

/** A timer scheduler that records delays and fires only when the test says so. */
class ManualTimerScheduler : TimerScheduler {
    /** One booked timer. */
    inner class ManualTimer(val delayMillis: Long, val action: Runnable) : ScheduledTimer {
        /** True once cancelled or fired. */
        @Volatile
        var isDone = false

        /** Cancels the timer. */
        override fun cancel() {
            isDone = true
        }
    }

    /** Every timer ever booked, in order. */
    private val bookedTimers = ArrayList<ManualTimer>()

    /** Books a timer. */
    @Synchronized
    override fun schedule(delayMillis: Long, action: Runnable): ScheduledTimer {
        val manualTimer = ManualTimer(delayMillis, action)
        bookedTimers.add(manualTimer)

        return manualTimer
    }

    /** The delays of timers that have neither fired nor been cancelled. */
    @Synchronized
    fun pendingDelays(): List<Long> {
        return bookedTimers.filter { timer -> !timer.isDone }.map { timer -> timer.delayMillis }
    }

    /** Fires every pending timer once; returns how many fired. */
    fun fireAll(): Int {
        val dueTimers = synchronized(this) { bookedTimers.filter { timer -> !timer.isDone } }

        for (dueTimer in dueTimers) {
            dueTimer.isDone = true
            dueTimer.action.run()
        }

        return dueTimers.size
    }

    /** Fires the pending timers booked with exactly this delay; returns how many fired. */
    fun fireWithDelay(delayMillis: Long): Int {
        val dueTimers = synchronized(this) { bookedTimers.filter { timer -> !timer.isDone && timer.delayMillis == delayMillis } }

        for (dueTimer in dueTimers) {
            dueTimer.isDone = true
            dueTimer.action.run()
        }

        return dueTimers.size
    }
}

/** One request a test transport or server received. */
class ReceivedRequest(
    /** The headers, by lower-case name. */
    val headers: Map<String, String>,

    /** The body, gunzipped when it was gzipped. */
    val decodedBody: String,

    /** The raw body as sent. */
    val rawBody: ByteArray,
) {
    /** The NDJSON lines parsed as objects. */
    @Suppress("UNCHECKED_CAST")
    val events: List<Map<String, Any?>>
        get() = decodedBody.split('\n').filter { line -> line.isNotEmpty() }.map { line -> JsonReader.parse(line) as Map<String, Any?> }

    /** The event ids in order. */
    val eventIds: List<String>
        get() = events.map { event -> event["event_id"] as String }
}

/** Decodes a request body, gunzipping when the header says so. */
fun decodeBody(rawBody: ByteArray, contentEncoding: String?): String {
    if (contentEncoding == "gzip") {
        return GZIPInputStream(ByteArrayInputStream(rawBody)).use { gzipStream -> String(gzipStream.readBytes(), Charsets.UTF_8) }
    }

    return String(rawBody, Charsets.UTF_8)
}

/** A transport that answers from a script and records every request; a null status means a network error. */
class ScriptedTransport(private val fallbackStatus: Int = 202) : IngestTransport {
    /** Answers still to give: status (null = network error) and Retry-After. */
    private val scriptedAnswers = ArrayDeque<Pair<Int?, String?>>()

    /** Every request received. */
    val requests: MutableList<ReceivedRequest> = java.util.Collections.synchronizedList(ArrayList())

    /** Queues one answer. */
    @Synchronized
    fun answer(statusCode: Int?, retryAfterHeader: String? = null) {
        scriptedAnswers.addLast(Pair(statusCode, retryAfterHeader))
    }

    /** Pops the next answer, or the fallback. */
    @Synchronized
    private fun nextAnswer(): Pair<Int?, String?> {
        return scriptedAnswers.removeFirstOrNull() ?: Pair(fallbackStatus, null)
    }

    /** Records the request and answers from the script. */
    override fun post(url: String, headers: Map<String, String>, body: ByteArray): IngestHttpResponse {
        val lowerCaseHeaders = headers.mapKeys { (headerName, _) -> headerName.lowercase() }
        requests.add(ReceivedRequest(lowerCaseHeaders, decodeBody(body, lowerCaseHeaders["content-encoding"]), body))

        val (statusCode, retryAfterHeader) = nextAnswer()
        if (statusCode == null) {
            throw IOException("scripted network error")
        }

        return IngestHttpResponse(statusCode, retryAfterHeader)
    }
}

/** Builds a client wired to test doubles. */
fun makeTestClient(
    storageDirectory: File,
    transport: IngestTransport,
    timerScheduler: TimerScheduler = ManualTimerScheduler(),
    clock: Clock = ManualClock(),
    logger: SdkLogger = RecordingLogger(),
    endpoint: String = "http://127.0.0.1:9",
    isInitiallyEnabled: Boolean = true,
    maxBatchEventCount: Int = 50,
    maxRequestBytes: Int = 256_000,
    jitterFraction: Double = 0.5,
): SimpleDataLogsClient {
    val configuration = ClientConfiguration(
        ingestKey = "sdl_pub_test",
        storageDirectory = storageDirectory,
        endpoint = endpoint,
        maxBatchEventCount = maxBatchEventCount,
        maxRequestBytes = maxRequestBytes,
        requestTimeoutMillis = 5_000,
    )
    val dependencies = ClientDependencies(
        clock = clock,
        jitterSource = { jitterFraction },
        transport = transport,
        timerScheduler = timerScheduler,
        logger = logger,
    )

    return SimpleDataLogsClient(configuration, dependencies, isInitiallyEnabled)
}

/** Waits for the client to go quiet, failing the test if it does not. */
fun SimpleDataLogsClient.settle() {
    check(awaitQuiet(10_000)) { "The client did not go quiet within 10 s" }
}

/** Events still in the outbox. */
fun SimpleDataLogsClient.pendingEvents(): Int {
    return inspectPipeline { pipeline -> pipeline.pendingEventCount } ?: -1
}

/** The sealed batches on disk, oldest first. */
internal fun SimpleDataLogsClient.sealedBatches(): List<SealedBatch> {
    return inspectPipeline { pipeline -> pipeline.sealedBatchSnapshot } ?: emptyList()
}
