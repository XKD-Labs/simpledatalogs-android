package com.xkdlabs.simpledatalogs

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor

/** Receives autocaptured events; the Android facade forwards them to its client. */
@InternalSimpleDataLogsApi
public interface AutocaptureEmitter {
    /** Sends one event with its own time and session id. */
    public fun emit(eventName: String, properties: Map<String, Any?>, occurredAtMillis: Long, sessionId: String?)

    /** Sets the session id on every later event. */
    public fun setSessionId(sessionId: String?)
}

/** Runs the enabled autocapture features and turns lifecycle moments into events. */
@InternalSimpleDataLogsApi
public class AutocaptureController(
    /** The features turned on. */
    private val options: Autocapture,

    /** The folder for the session file, the crash file and the first-launch marker. */
    private val stateDirectory: File,

    /** Where events go. */
    private val emitter: AutocaptureEmitter,

    /** Runs file reads and writes off the caller's thread. */
    private val ioExecutor: Executor,

    /** Stamps the crash time. */
    private val clock: Clock = Clock.SYSTEM,

    /** Where problems are logged. */
    private val logger: SdkLogger = JavaUtilSdkLogger,
) {
    /** File names and event names. */
    public companion object {
        /** The session file, kept beside the outbox. */
        public const val SESSION_FILE_NAME: String = "session.json"

        /** The marker written on the first launch. */
        public const val FIRST_LAUNCH_MARKER_NAME: String = "first-launch.marker"
    }

    /** Guards the session tracker. */
    private val sessionLock = Any()

    /** The session state; loaded from disk on first use. */
    private var sessionTracker: SessionTracker? = null

    /** True once this process reported `app_launched`. */
    private var hasReportedLaunch = false

    /** The launch: sends the last launch's crash, then installs the crash handler. */
    public fun start() {
        if (Autocapture.CRASHES !in options) {
            return
        }

        // A crash saved by the last launch goes first, stamped when it happened, with no session.
        ioExecutor.execute {
            val pendingCrash = CrashRecorder.takePendingCrash(stateDirectory)
            if (pendingCrash != null) {
                val crashProperties = linkedMapOf<String, Any?>(
                    "exception_name" to pendingCrash.exceptionName,
                    "reason" to pendingCrash.reason,
                    "stack" to pendingCrash.stack,
                )
                emitter.emit("app_crashed", crashProperties, pendingCrash.occurredAtMillis, null)
            }
        }

        CrashRecorder.install(stateDirectory, clock)
    }

    /** The app came to the foreground: ends a stale session, starts or resumes one, and reports the launch once. */
    public fun appBecameActive(nowMillis: Long) {
        var currentSessionId: String? = null

        // Feed the moment to the session tracker and send what it produced, in order.
        if (Autocapture.SESSIONS in options) {
            val (changes, sessionSnapshot) = synchronized(sessionLock) {
                val tracker = loadedSessionTracker()
                val trackerChanges = tracker.appBecameActive(nowMillis) { UUID.randomUUID().toString() }

                Pair(trackerChanges, tracker.session)
            }

            for (change in changes) {
                when (change) {
                    is SessionTracker.Change.Ended -> emitter.emit("session_end", mapOf("duration_s" to change.durationSeconds), change.atMillis, change.sessionId)
                    is SessionTracker.Change.Started -> {
                        emitter.setSessionId(change.sessionId)
                        emitter.emit("session_start", emptyMap(), change.atMillis, change.sessionId)
                    }
                }
            }

            currentSessionId = sessionSnapshot?.id
            emitter.setSessionId(currentSessionId)
            writeSessionInBackground(sessionSnapshot)
        }

        // The first foreground of this process is the launch.
        if (Autocapture.APP_LAUNCHES in options && markLaunchReported()) {
            val launchSessionId = currentSessionId
            ioExecutor.execute {
                emitter.emit("app_launched", mapOf("is_first_launch" to markFirstLaunch()), nowMillis, launchSessionId)
            }
        }
    }

    /** The app went to the background: saves when, so a later launch can end the session. */
    public fun appWentToBackground(nowMillis: Long) {
        if (Autocapture.SESSIONS !in options) {
            return
        }

        val sessionSnapshot = synchronized(sessionLock) {
            val tracker = loadedSessionTracker()
            tracker.appWentToBackground(nowMillis)

            tracker.session
        }

        writeSessionInBackground(sessionSnapshot)
    }

    /** The current session id, if any. */
    public val currentSessionId: String?
        get() = synchronized(sessionLock) { sessionTracker?.session?.id }

    // Returns the tracker, reading the session a previous launch saved on first use; call under sessionLock.
    private fun loadedSessionTracker(): SessionTracker {
        val existingTracker = sessionTracker
        if (existingTracker != null) {
            return existingTracker
        }

        val loadedTracker = SessionTracker(readSession())
        sessionTracker = loadedTracker

        return loadedTracker
    }

    // True the first time it is called in this process.
    private fun markLaunchReported(): Boolean {
        synchronized(sessionLock) {
            if (hasReportedLaunch) {
                return false
            }

            hasReportedLaunch = true
            return true
        }
    }

    // True on the very first launch; writes the marker so later launches say false.
    private fun markFirstLaunch(): Boolean {
        val markerFile = File(stateDirectory, FIRST_LAUNCH_MARKER_NAME)
        if (markerFile.exists()) {
            return false
        }

        try {
            stateDirectory.mkdirs()
            markerFile.createNewFile()
        } catch (markerError: IOException) {
            logger.error("Could not write the first-launch marker", markerError)
        }

        return true
    }

    // Saves the session off the caller's thread, or deletes the file when there is none.
    private fun writeSessionInBackground(sessionSnapshot: SessionTracker.Session?) {
        ioExecutor.execute {
            val sessionFile = File(stateDirectory, SESSION_FILE_NAME)

            if (sessionSnapshot == null) {
                sessionFile.delete()
                return@execute
            }

            // Write a temporary file, then rename it, so a crash never leaves half a session.
            try {
                stateDirectory.mkdirs()
                val temporaryFile = File(stateDirectory, "$SESSION_FILE_NAME.tmp")
                temporaryFile.writeText(sessionSnapshot.toJson(), Charsets.UTF_8)

                if (!temporaryFile.renameTo(sessionFile)) {
                    logger.error("Could not save the session")
                }
            } catch (writeError: IOException) {
                logger.error("Could not save the session", writeError)
            }
        }
    }

    // The session a previous launch saved, if readable.
    private fun readSession(): SessionTracker.Session? {
        val sessionFile = File(stateDirectory, SESSION_FILE_NAME)
        if (!sessionFile.isFile) {
            return null
        }

        return try {
            SessionTracker.Session.fromJsonOrNull(sessionFile.readText(Charsets.UTF_8))
        } catch (readError: IOException) {
            null
        }
    }
}
