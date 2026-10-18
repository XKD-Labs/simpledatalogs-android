package com.xkdlabs.simpledatalogs

import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** A simpledatalogs client for the JVM: track events from any thread; they go out in batches through a crash-safe disk outbox. */
public class SimpleDataLogsClient @InternalSimpleDataLogsApi constructor(
    /** Every tunable. */
    private val configuration: ClientConfiguration,

    /** Clock, jitter, transport, timers and logger. */
    private val dependencies: ClientDependencies = ClientDependencies(),

    /** False when the user opted out before the client started, so leftovers are deleted, never sent. */
    isInitiallyEnabled: Boolean = true,
) : Closeable {
    /** Creates a server client that flushes every `batchIntervalMillis` (5 s by default) and keeps its outbox in `storageDirectory`. */
    @JvmOverloads
    public constructor(
        ingestKey: String,
        storageDirectory: File,
        endpoint: String = DEFAULT_ENDPOINT,
        batchIntervalMillis: Long = SERVER_BATCH_INTERVAL_MILLIS,
    ) : this(
        ClientConfiguration(
            ingestKey = ingestKey.trim(),
            storageDirectory = storageDirectory,
            endpoint = endpoint,
            batchIntervalMillis = batchIntervalMillis,
        ),
    )

    /** SDK constants. */
    public companion object {
        /** This SDK's version, sent in every User-Agent. */
        public const val SDK_VERSION: String = "0.1.0"

        /** The production ingest host. */
        public const val DEFAULT_ENDPOINT: String = "https://edge.simpledatalogs.com"

        /** The batch interval servers use: a partial batch goes out 5 s after its first event. */
        public const val SERVER_BATCH_INTERVAL_MILLIS: Long = 5_000

        /** The batch interval apps use: a partial batch goes out 30 s after its first event. */
        public const val APP_BATCH_INTERVAL_MILLIS: Long = 30_000

        /** How long `close()` waits for a last flush. */
        public const val DEFAULT_CLOSE_TIMEOUT_MILLIS: Long = 5_000
    }

    /** Where problems are logged. */
    private val logger = dependencies.logger

    /** The worker thread, captured so `flushBlocking` can refuse to wait on itself. */
    @Volatile
    private var workerThread: Thread? = null

    /** The single thread every outbox and pipeline change runs on. */
    private val worker: ScheduledExecutorService = ScheduledThreadPoolExecutor(1, namedDaemonThreadFactory("simpledatalogs-worker") { createdThread -> workerThread = createdThread }).apply {
        removeOnCancelPolicy = true
    }

    /** The thread blocking HTTP requests run on. */
    private val network: ExecutorService = Executors.newSingleThreadExecutor(namedDaemonThreadFactory("simpledatalogs-network") { })

    /** The pipeline, confined to the worker thread. */
    private val pipeline: EventPipeline

    /** Guards the settings below and keeps track calls in call order. */
    private val stateLock = Any()

    /** The anonymous device id attached to every event. */
    private var deviceId: String? = null

    /** The current session id, set by autocapture's sessions. */
    private var sessionId: String? = null

    /** Properties merged into every event's props. */
    private var defaultProperties: Map<String, Any> = emptyMap()

    /** False after an opt-out: events are ignored and the outbox is deleted. */
    private var isEnabled = isInitiallyEnabled

    /** True once `close()` ran. */
    private var isClosed = false

    /** True once the pending-track cap was logged, so a hot loop logs it once. */
    private var hasLoggedPendingTrackCap = false

    /** Track calls queued for the worker and not yet journaled. */
    private val pendingTrackCount = AtomicInteger(0)

    init {
        // Build the pipeline around the worker; nothing touches the disk on the caller's thread.
        val timerScheduler = dependencies.timerScheduler ?: ExecutorTimerScheduler(worker)
        pipeline = EventPipeline(configuration, dependencies, worker, network, timerScheduler, isInitiallyEnabled)

        // Recover the outbox and resend what an earlier run left.
        executeOnWorker { pipeline.start() }
    }

    // Public API

    /** Records one event; it returns at once and never throws. Properties may be strings, numbers or booleans. */
    @JvmOverloads
    public fun track(eventName: String, properties: Map<String, Any?> = emptyMap()) {
        trackAt(eventName, properties, dependencies.clock.currentTimeMillis(), null, isSessionIdExplicit = false)
    }

    /** Records a screen view: `screen_view` with a `screen` prop. */
    @JvmOverloads
    public fun trackScreen(screenName: String, properties: Map<String, Any?> = emptyMap()) {
        track(SCREEN_VIEW_EVENT_NAME, properties + (SCREEN_PROPERTY_NAME to screenName))
    }

    /** Sets the anonymous device id on every later event, or clears it with null. */
    public fun setDeviceId(deviceId: String?) {
        synchronized(stateLock) {
            this.deviceId = deviceId
        }
    }

    /** Replaces the properties merged into every later event, e.g. app_version or region. */
    public fun setDefaultProperties(properties: Map<String, Any?>) {
        val validProperties = Properties.sanitize(properties, logger)

        synchronized(stateLock) {
            defaultProperties = validProperties
        }
    }

    /** Turns collection on or off; turning it off also deletes every event not yet delivered. */
    public fun setEnabled(isEnabled: Boolean) {
        synchronized(stateLock) {
            this.isEnabled = isEnabled
            executeOnWorker { pipeline.setEnabled(isEnabled) }
        }
    }

    /** Seals and starts sending everything tracked so far, without waiting. */
    public fun flush() {
        executeOnWorker { pipeline.flush(null) }
    }

    /** Seals and sends everything now, waiting up to `timeoutMillis`; true when the outbox is empty afterwards. Never call it on the main thread. */
    public fun flushBlocking(timeoutMillis: Long): Boolean {
        // Waiting on the worker from the worker would never end.
        if (Thread.currentThread() === workerThread) {
            logger.error("flushBlocking was called on the SDK's own worker thread; skipped")
            return false
        }

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

        // Start the attempt and wait for it to end.
        val attemptEnded = CountDownLatch(1)
        if (!executeOnWorker { pipeline.flush { attemptEnded.countDown() } }) {
            return false
        }

        try {
            attemptEnded.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)

            // Then ask the worker whether anything is still waiting.
            return runOnWorkerAndWait(remainingMillis(deadlineNanos)) { pipeline.pendingEventCount == 0 } ?: false
        } catch (interruptedError: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    /** Flushes for up to 5 s, then stops the SDK's threads; later calls are ignored. Undelivered events stay on disk. */
    override fun close() {
        shutdown(DEFAULT_CLOSE_TIMEOUT_MILLIS)
    }

    /** Flushes for up to `flushTimeoutMillis`, then stops the SDK's threads. */
    public fun shutdown(flushTimeoutMillis: Long) {
        synchronized(stateLock) {
            if (isClosed) {
                return
            }

            isClosed = true
        }

        // A last flush, then stop timers and threads; the outbox keeps whatever did not make it.
        flushBlocking(flushTimeoutMillis)
        executeOnWorker { pipeline.cancelBackgroundWork() }
        worker.shutdown()
        network.shutdownNow()
    }

    // Plumbing for the Android library and autocapture

    /** Records an event with its own time and session id, e.g. a session_end or a crash from the last launch. */
    @InternalSimpleDataLogsApi
    public fun trackAt(eventName: String, properties: Map<String, Any?>, occurredAtMillis: Long, sessionId: String?) {
        trackAt(eventName, properties, occurredAtMillis, sessionId, isSessionIdExplicit = true)
    }

    /** Sets the session id on every later event; autocapture's sessions call it. */
    @InternalSimpleDataLogsApi
    public fun setSessionId(sessionId: String?) {
        synchronized(stateLock) {
            this.sessionId = sessionId
        }
    }

    /** Runs a small task on the SDK's worker thread, after everything queued before it. */
    @InternalSimpleDataLogsApi
    public fun executeInBackground(task: Runnable) {
        executeOnWorker { task.run() }
    }

    /** True when collection is on. */
    @InternalSimpleDataLogsApi
    public val isCollectionEnabled: Boolean
        get() = synchronized(stateLock) { isEnabled }

    // Test hooks

    /** Waits until no track is queued and no send is in flight; for tests. */
    internal fun awaitQuiet(timeoutMillis: Long): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

        while (remainingMillis(deadlineNanos) > 0) {
            val isBusy = runOnWorkerAndWait(remainingMillis(deadlineNanos)) { pipeline.isSending || pendingTrackCount.get() > 0 }
            if (isBusy == false) {
                return true
            }

            Thread.sleep(2)
        }

        return false
    }

    /** Reads pipeline state on the worker; for tests. */
    internal fun <T> inspectPipeline(read: (EventPipeline) -> T): T? {
        return runOnWorkerAndWait(5_000) { read(pipeline) }
    }

    // Private helpers

    // Stamps and queues one event, keeping call order, within the pending-track cap.
    private fun trackAt(eventName: String, properties: Map<String, Any?>, occurredAtMillis: Long, explicitSessionId: String?, isSessionIdExplicit: Boolean) {
        // An empty name cannot become an event.
        if (eventName.isEmpty()) {
            logger.error("track was called with an empty event name; event dropped")
            return
        }

        // Drop values JSON or ingest cannot carry, before the event is stamped.
        val validProperties = Properties.sanitize(properties, logger)

        synchronized(stateLock) {
            if (!isEnabled) {
                return
            }

            if (isClosed) {
                logger.error("track($eventName) was called after the client was closed; event dropped")
                return
            }

            // A host loop faster than the disk skips events instead of growing memory; they never got a 202, so no promise breaks.
            if (pendingTrackCount.get() >= configuration.maxPendingTrackCount) {
                if (!hasLoggedPendingTrackCap) {
                    hasLoggedPendingTrackCap = true
                    logger.warning("More than ${configuration.maxPendingTrackCount} events wait for the disk; new events are skipped until it catches up")
                }

                return
            }

            // Stamp event_id and ts now, when the event happens.
            val trackedEvent = TrackedEvent(
                eventName = eventName,
                eventId = UUID.randomUUID().toString(),
                occurredAtMillis = occurredAtMillis,
                deviceId = deviceId,
                sessionId = if (isSessionIdExplicit) explicitSessionId else sessionId,
                properties = Properties.merge(defaultProperties, validProperties),
            )

            // Queue it under the lock, so events from many threads keep call order.
            pendingTrackCount.incrementAndGet()
            val wasQueued = executeOnWorker {
                pipeline.enqueue(trackedEvent)
                pendingTrackCount.decrementAndGet()
            }

            if (!wasQueued) {
                pendingTrackCount.decrementAndGet()
            }
        }
    }

    // Runs an action on the worker; false when the worker has shut down.
    private fun executeOnWorker(action: () -> Unit): Boolean {
        return try {
            worker.execute {
                try {
                    action()
                } catch (unexpectedError: RuntimeException) {
                    // Fail open: a bug in the SDK must never kill its worker or the host.
                    logger.error("Unexpected SDK error", unexpectedError)
                }
            }

            true
        } catch (rejectedError: RejectedExecutionException) {
            false
        }
    }

    // Runs a read on the worker and waits for its result, or returns null on timeout or shutdown.
    private fun <T> runOnWorkerAndWait(timeoutMillis: Long, read: () -> T): T? {
        val readTask = FutureTask { read() }

        return try {
            worker.execute(readTask)
            readTask.get(maxOf(timeoutMillis, 0), TimeUnit.MILLISECONDS)
        } catch (rejectedError: RejectedExecutionException) {
            null
        } catch (timeoutError: TimeoutException) {
            null
        } catch (executionError: java.util.concurrent.ExecutionException) {
            logger.error("Unexpected SDK error", executionError.cause)
            null
        }
    }

    // Milliseconds left until a System.nanoTime deadline, never negative.
    private fun remainingMillis(deadlineNanos: Long): Long {
        return maxOf(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()))
    }
}

/** The event name `trackScreen` sends. */
internal const val SCREEN_VIEW_EVENT_NAME = "screen_view"

/** The prop that carries the screen name. */
internal const val SCREEN_PROPERTY_NAME = "screen"

/** The production timer scheduler: the worker's own scheduled executor. */
private class ExecutorTimerScheduler(private val scheduledExecutor: ScheduledExecutorService) : TimerScheduler {
    /** Schedules the action, or returns a no-op handle once the executor has shut down. */
    override fun schedule(delayMillis: Long, action: Runnable): ScheduledTimer {
        return try {
            val scheduledFuture = scheduledExecutor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
            object : ScheduledTimer {
                override fun cancel() {
                    scheduledFuture.cancel(false)
                }
            }
        } catch (rejectedError: RejectedExecutionException) {
            object : ScheduledTimer {
                override fun cancel() = Unit
            }
        }
    }
}

// Builds a thread factory for named daemon threads, so the SDK never keeps a JVM alive.
private fun namedDaemonThreadFactory(threadName: String, onThreadCreated: (Thread) -> Unit): ThreadFactory {
    return ThreadFactory { runnable ->
        val createdThread = Thread(runnable, threadName)
        createdThread.isDaemon = true
        onThreadCreated(createdThread)

        createdThread
    }
}
