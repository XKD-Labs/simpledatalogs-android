package com.xkdlabs.simpledatalogs

import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Batching, the disk outbox, and a send loop with backoff and 401 re-probes; every method runs on the worker thread. */
internal class EventPipeline(
    /** Every tunable. */
    private val configuration: ClientConfiguration,

    /** Clock, jitter and logger. */
    private val dependencies: ClientDependencies,

    /** The single worker thread every state change runs on. */
    private val worker: Executor,

    /** The thread blocking HTTP requests run on, so tracking never waits for the network. */
    private val network: Executor,

    /** Runs batch, retry and probe timers. */
    private val timerScheduler: TimerScheduler,

    /** False after an opt-out. */
    private var isEnabled: Boolean,
) {
    /** A timer whose action runs on the worker, unless it was cancelled first. */
    private inner class WorkerTimer(private val action: () -> Unit) {
        /** Set by `cancel()`; read on the worker when the timer fires. */
        var isCancelled = false

        /** The underlying scheduled handle. */
        var scheduledTimer: ScheduledTimer? = null

        /** Hops onto the worker and runs the action if still wanted. */
        fun fire() {
            executeOnWorker {
                if (!isCancelled) {
                    action()
                }
            }
        }

        /** Stops the action from running. */
        fun cancel() {
            isCancelled = true
            scheduledTimer?.cancel()
        }
    }

    /** Where problems are logged. */
    private val logger = dependencies.logger

    /** The journal and the sealed batches on disk. */
    private val outbox = EventOutbox(configuration.storageDirectory, configuration.outboxLimits, dependencies.clock, logger)

    /** Sends one batch and classifies the answer. */
    private val ingestClient = IngestClient(
        ingestUrl = configuration.ingestUrl,
        ingestKey = configuration.ingestKey,
        userAgent = configuration.userAgent,
        gzipThresholdBytes = configuration.gzipThresholdBytes,
        transport = dependencies.transport ?: HttpUrlConnectionTransport(configuration.requestTimeoutMillis),
        clock = dependencies.clock,
    )

    /** True once `start()` has recovered the outbox. */
    private var hasStarted = false

    /** True while a 401 holds sending until a re-probe gets through. */
    var isSendingPaused = false
        private set

    /** True while waiting out a backoff after a failed send. */
    var isBackingOff = false
        private set

    /** True while a send loop runs, including while its request is in flight. */
    var isSending = false
        private set

    /** Failed sends in a row, which sets the backoff ceiling. */
    private var consecutiveFailureCount = 0

    /** Re-probes booked during the current 401 pause. */
    private var invalidKeyProbeCount = 0

    /** The batch that last failed to read. */
    private var unreadableStreakBatch: SealedBatch? = null

    /** How many times in a row that batch failed to read. */
    private var unreadableStreakCount = 0

    /** Fires when a partial batch has waited the batch interval. */
    private var batchTimer: WorkerTimer? = null

    /** Fires when a backoff ends. */
    private var retryTimer: WorkerTimer? = null

    /** Fires when it is time to re-probe a rejected key. */
    private var invalidKeyProbeTimer: WorkerTimer? = null

    /** Identifies the newest send loop, so an answer to a cancelled one is ignored. */
    private var sendLoopGeneration = 0

    /** Callbacks waiting for the current send attempt to end. */
    private val flushWaiters = ArrayList<() -> Unit>()

    /** Events still waiting in the journal and the sealed batches. */
    val pendingEventCount: Int
        get() = outbox.totalEventCount

    /** The sealed batches, oldest first; for tests. */
    val sealedBatchSnapshot: List<SealedBatch>
        get() = outbox.sealedBatchSnapshot

    // Commands

    /** Recovers the outbox from disk, then resends whatever a previous launch could not deliver. */
    fun start() {
        if (hasStarted) {
            return
        }

        hasStarted = true

        // Seal leftover journals and index the sealed batches; a broken disk only costs persistence.
        try {
            outbox.recoverAfterLaunch()
        } catch (recoveryError: IOException) {
            logger.error("Could not create the outbox folder", recoveryError)
        }

        // An opted-out user's leftovers are deleted, never sent.
        if (!isEnabled) {
            outbox.deleteEverything()
            return
        }

        // The first send of a launch doubles as the re-probe for a key the last launch saw rejected.
        startSendingIfIdle()
    }

    /** Journals one event, sealing and sending as soon as the batch is full. */
    fun enqueue(event: TrackedEvent) {
        if (!isEnabled) {
            return
        }

        // Encode now, so the disk only ever holds complete lines.
        val eventLine = event.encodedLine().toByteArray(Charsets.UTF_8)

        // An event that alone overfills a request body could never be sent.
        if (EventOutbox.storedByteCount(eventLine) > configuration.maxRequestBytes) {
            logger.error("Dropped event ${event.eventName}: ${eventLine.size} bytes is over the request limit")
            return
        }

        // Seal first when the journal is full; if that fails, drop this event rather than overfill a request.
        if (outbox.wouldJournalOverflow(eventLine) && !sealJournalAndSend()) {
            logger.error("Dropped event ${event.eventName}: the full journal could not be sealed")
            return
        }

        // Journal it, so a crash from here on loses nothing.
        try {
            outbox.appendToJournal(eventLine)
        } catch (writeError: IOException) {
            logger.error("Dropped event ${event.eventName}: the journal is not writable", writeError)
            return
        }

        // A full batch goes out now; a partial one waits for the batch timer.
        if (outbox.journalEventCount >= configuration.maxBatchEventCount) {
            sealJournalAndSend()
        } else {
            startBatchTimerIfNeeded()
        }
    }

    /** Turns collection on or off; off stops all sending and deletes every unsent event. */
    fun setEnabled(shouldEnable: Boolean) {
        if (shouldEnable == isEnabled) {
            return
        }

        isEnabled = shouldEnable

        // Turning collection back on needs nothing more; the next event starts a new batch.
        if (shouldEnable) {
            return
        }

        // Stop every timer and any in-flight send, then remove everything not yet delivered.
        cancelBackgroundWork()
        outbox.deleteEverything()
        logger.info("Collection disabled; deleted every unsent event")
    }

    /** Seals the journal and sends every sealed batch now; `onAttemptEnded` runs when that attempt ends. */
    fun flush(onAttemptEnded: (() -> Unit)?) {
        if (!isEnabled) {
            onAttemptEnded?.invoke()
            return
        }

        sealJournalAndSend()

        // With no loop running (empty, paused or backing off), the attempt is already over.
        if (onAttemptEnded == null) {
            return
        }

        if (isSending) {
            flushWaiters.add(onAttemptEnded)
        } else {
            onAttemptEnded()
        }
    }

    /** Cancels every timer and any in-flight send, and forgets backoff and 401 state. */
    fun cancelBackgroundWork() {
        batchTimer?.cancel()
        retryTimer?.cancel()
        invalidKeyProbeTimer?.cancel()

        batchTimer = null
        retryTimer = null
        invalidKeyProbeTimer = null

        // A new generation makes any in-flight answer land nowhere.
        sendLoopGeneration += 1
        finishSendLoop()

        // With no timers left, nothing would ever lift a pause or a backoff, so reset both.
        isSendingPaused = false
        isBackingOff = false
        consecutiveFailureCount = 0
        invalidKeyProbeCount = 0
        unreadableStreakBatch = null
        unreadableStreakCount = 0
    }

    // Batching

    // Seals the journal and makes sure the send loop is running; returns false when sealing failed.
    private fun sealJournalAndSend(): Boolean {
        val didSeal = sealJournal()
        startSendingIfIdle()

        return didSeal
    }

    // Seals the journal into a batch without starting a send; returns false when sealing failed.
    private fun sealJournal(): Boolean {
        try {
            outbox.sealJournal()
        } catch (sealError: IOException) {
            // Keep (or restart) the batch timer, so the partial batch is tried again later.
            logger.error("Could not seal the journal", sealError)
            startBatchTimerIfNeeded()
            return false
        }

        // A sealed journal no longer needs its timer.
        batchTimer?.cancel()
        batchTimer = null

        return true
    }

    // Starts the batch timer when a partial batch begins.
    private fun startBatchTimerIfNeeded() {
        if (batchTimer != null) {
            return
        }

        batchTimer = scheduleOnWorker(configuration.batchIntervalMillis) {
            batchTimer = null

            // While a backoff or a 401 pause holds sending, the journal keeps growing instead of sealing one tiny file per interval.
            if (canSendNow) {
                sealJournalAndSend()
            }
        }
    }

    // Sending

    /** True when nothing holds sending back right now. */
    private val canSendNow: Boolean
        get() = isEnabled && !isSendingPaused && !isBackingOff

    // Starts the send loop unless it is already running, paused by a 401, or waiting out a backoff.
    private fun startSendingIfIdle() {
        if (isSending) {
            return
        }

        if (canSendNow && outbox.oldestSealedBatch != null) {
            startSendLoop(null)
        }
    }

    // Launches a send loop, sending a 401 probe batch first when one is given.
    private fun startSendLoop(probeBatch: SealedBatch?) {
        sendLoopGeneration += 1
        isSending = true

        if (probeBatch != null) {
            sendBatch(probeBatch, sendLoopGeneration, isProbe = true)
        } else {
            continueSendLoop(sendLoopGeneration, shouldKeepSending = true)
        }
    }

    // Sends the next batch while every answer is a 202 (or a 413 split), else ends the loop.
    private fun continueSendLoop(generation: Int, shouldKeepSending: Boolean) {
        if (generation != sendLoopGeneration) {
            return
        }

        val nextBatch = if (shouldKeepSending && canSendNow) outbox.oldestSealedBatch else null
        if (nextBatch == null) {
            finishSendLoop()
            return
        }

        sendBatch(nextBatch, generation, isProbe = false)
    }

    // Reads one batch and posts it on the network thread; the answer comes back to the worker.
    private fun sendBatch(batch: SealedBatch, generation: Int, isProbe: Boolean) {
        // A vanished file is skipped; an unreadable one is retried, then set aside.
        val batchBody = try {
            outbox.body(batch)
        } catch (readError: IOException) {
            afterSend(generation, isProbe, handleUnreadableBatch(batch, readError))
            return
        }

        if (batchBody == null) {
            afterSend(generation, isProbe, shouldKeepSending = true)
            return
        }

        // A readable batch ends any read-failure streak.
        unreadableStreakBatch = null
        unreadableStreakCount = 0

        // The request blocks, so it runs off the worker; the answer is applied back on the worker.
        try {
            network.execute {
                val outcome = ingestClient.send(batchBody)
                executeOnWorker { handleOutcome(generation, batch, batchBody, outcome, isProbe) }
            }
        } catch (rejectedError: RejectedExecutionException) {
            finishSendLoop()
        }
    }

    // Applies the ingest API rule for one answer, then continues or ends the loop.
    private fun handleOutcome(generation: Int, batch: SealedBatch, batchBody: ByteArray, outcome: IngestOutcome, isProbe: Boolean) {
        // An opt-out or shutdown mid-request already dealt with the outbox, so this answer no longer matters.
        if (generation != sendLoopGeneration) {
            return
        }

        if (!isEnabled) {
            finishSendLoop()
            return
        }

        val shouldKeepSending = when (outcome) {
            is IngestOutcome.Accepted -> {
                endInvalidKeyPauseIfNeeded()
                consecutiveFailureCount = 0
                outbox.delete(batch)
                true
            }
            is IngestOutcome.InvalidKey -> {
                pauseForInvalidKey()
                false
            }
            is IngestOutcome.PayloadTooLarge -> {
                // Ingest authenticates before it weighs the body, so a 413 also proves the key works.
                endInvalidKeyPauseIfNeeded()
                splitTooLargeBatch(batch, batchBody)
            }
            is IngestOutcome.Unsupported -> {
                // Resending it unchanged can never work, so it is set aside and the next batch goes.
                endInvalidKeyPauseIfNeeded()
                logger.error("Ingest refused a batch of ${batch.eventCount} events as unsupported (415); it was set aside")
                outbox.setAside(batch, EventOutbox.UNSUPPORTED_EXTENSION)
                true
            }
            is IngestOutcome.Retryable -> {
                handleFailedSend(outcome.retryAfterMillis, outcome.reason)
                false
            }
        }

        afterSend(generation, isProbe, shouldKeepSending)
    }

    // After any send: a probe that left the pause standing needs its next probe, then the loop goes on.
    private fun afterSend(generation: Int, isProbe: Boolean, shouldKeepSending: Boolean) {
        if (isProbe && isSendingPaused && invalidKeyProbeTimer == null) {
            scheduleInvalidKeyProbe(null)
        }

        continueSendLoop(generation, shouldKeepSending)
    }

    // Marks the loop idle and releases everyone waiting for it.
    private fun finishSendLoop() {
        isSending = false

        val waitingCallbacks = flushWaiters.toList()
        flushWaiters.clear()
        for (waitingCallback in waitingCallbacks) {
            waitingCallback()
        }
    }

    // Backs off after a failed send; during a 401 pause it books the next probe instead, since the key is still unproven.
    private fun handleFailedSend(retryAfterMillis: Long?, reason: String) {
        if (!isSendingPaused) {
            scheduleRetry(retryAfterMillis, reason)
            return
        }

        // A probe lost in transit (offline, timeout, 503) says nothing about the key, so the pause holds.
        logger.info("Key re-probe got no answer ($reason); sending stays paused")
        scheduleInvalidKeyProbe(retryAfterMillis)
    }

    // Retries an unreadable batch a few times, then sets it aside so it cannot block newer batches.
    private fun handleUnreadableBatch(batch: SealedBatch, readError: IOException): Boolean {
        // Count failures in a row on this same batch.
        unreadableStreakCount = if (unreadableStreakBatch == batch) unreadableStreakCount + 1 else 1
        unreadableStreakBatch = batch

        // After a few tries the batch is set aside, so it can no longer block every newer batch.
        if (unreadableStreakCount >= configuration.maxBatchReadAttempts) {
            outbox.setAside(batch, EventOutbox.UNREADABLE_EXTENSION)
            logger.error("Set aside a batch of ${batch.eventCount} events that failed to read $unreadableStreakCount times in a row")
            unreadableStreakBatch = null
            unreadableStreakCount = 0
            return true
        }

        // Otherwise wait and read it again, like any failed send.
        handleFailedSend(null, "an unreadable batch file (${readError.message})")
        return false
    }

    // Splits a batch the server called too large; returns whether to keep sending.
    private fun splitTooLargeBatch(batch: SealedBatch, batchBody: ByteArray): Boolean {
        return try {
            when (outbox.splitInHalf(batch, batchBody)) {
                BatchSplitOutcome.SPLIT -> logger.info("Ingest answered 413; split a batch of ${batch.eventCount} events in half")
                BatchSplitOutcome.DROPPED_LONE_EVENT -> logger.error("Ingest answered 413 for a single event; dropped it because it can never be accepted")
                BatchSplitOutcome.ALREADY_REMOVED -> Unit
            }

            true
        } catch (splitError: IOException) {
            // Only a failed half-write (e.g. a full disk) resends a batch unchanged; after a backoff it is split again.
            scheduleRetry(null, "the halves of a too-large batch could not be written")
            false
        }
    }

    // Backoff

    // Waits out a full-jitter backoff, never shorter than Retry-After, then resumes sending.
    private fun scheduleRetry(retryAfterMillis: Long?, reason: String) {
        consecutiveFailureCount += 1

        // Work out the wait from the failure streak and any server hint.
        val retryDelayMillis = configuration.retryPolicy.delayMillis(consecutiveFailureCount, retryAfterMillis, dependencies.jitterSource())
        logger.info("Send failed ($reason); retrying in $retryDelayMillis ms")

        // Hold every send until the timer fires.
        isBackingOff = true
        retryTimer?.cancel()
        retryTimer = scheduleOnWorker(retryDelayMillis) {
            retryTimer = null
            isBackingOff = false

            // Events journaled during the backoff go out with this attempt.
            sealJournal()
            startSendingIfIdle()
        }
    }

    // 401 pause

    // Pauses sending after a 401, keeping every event, and books a re-probe.
    private fun pauseForInvalidKey() {
        // Log the configuration error once, when the pause begins, not on every failed re-probe.
        if (!isSendingPaused) {
            logger.error("Ingest rejected the key (401); sending is paused and events are kept. Check the key passed to configure.")
        }

        isSendingPaused = true
        scheduleInvalidKeyProbe(null)
    }

    // Books the next 401 re-probe: a minute after the first rejection, then every 10 minutes, or later if Retry-After says so.
    private fun scheduleInvalidKeyProbe(retryAfterMillis: Long?) {
        val probeDelayMillis = configuration.retryPolicy.invalidKeyProbeDelayMillis(invalidKeyProbeCount, retryAfterMillis)
        invalidKeyProbeCount += 1

        invalidKeyProbeTimer?.cancel()
        invalidKeyProbeTimer = scheduleOnWorker(probeDelayMillis) {
            invalidKeyProbeTimer = null
            sendInvalidKeyProbe()
        }
    }

    // Sends the smallest sealed batch as one small request, to learn whether the key works yet.
    private fun sendInvalidKeyProbe() {
        if (!isEnabled || !isSendingPaused) {
            return
        }

        // Events tracked during the pause are probe candidates too.
        sealJournal()

        // With nothing to send, or a loop still finishing, there is nothing to learn yet.
        val probeBatch = outbox.smallestSealedBatch
        if (isSending || probeBatch == null) {
            scheduleInvalidKeyProbe(null)
            return
        }

        startSendLoop(probeBatch)
    }

    // Ends a 401 pause once a 202 or 413 shows the key is accepted again.
    private fun endInvalidKeyPauseIfNeeded() {
        if (!isSendingPaused) {
            return
        }

        isSendingPaused = false
        invalidKeyProbeCount = 0
        invalidKeyProbeTimer?.cancel()
        invalidKeyProbeTimer = null

        logger.info("Ingest accepted the key again; sending resumed")
    }

    // Threading helpers

    // Schedules an action to run on the worker after a delay.
    private fun scheduleOnWorker(delayMillis: Long, action: () -> Unit): WorkerTimer {
        val workerTimer = WorkerTimer(action)
        workerTimer.scheduledTimer = timerScheduler.schedule(delayMillis) { workerTimer.fire() }

        return workerTimer
    }

    // Runs an action on the worker, ignoring a worker that has shut down.
    private fun executeOnWorker(action: () -> Unit) {
        try {
            worker.execute(action)
        } catch (rejectedError: RejectedExecutionException) {
            // The client is closed; whatever is on disk waits for the next launch.
        }
    }
}
