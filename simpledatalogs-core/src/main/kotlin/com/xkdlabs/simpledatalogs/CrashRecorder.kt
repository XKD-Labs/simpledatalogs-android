package com.xkdlabs.simpledatalogs

import java.io.File
import java.io.FileOutputStream

/** A crash saved at the moment of an uncaught exception, sent on the next launch. */
@InternalSimpleDataLogsApi
public data class PendingCrash(
    /** The exception's class name, e.g. `java.lang.IllegalStateException`. */
    val exceptionName: String,

    /** The exception's message. */
    val reason: String,

    /** The stack, one frame per line, bounded. */
    val stack: String,

    /** When the crash happened, in epoch milliseconds. */
    val occurredAtMillis: Long,
)

/** Writes and reads the crash file through the default uncaught exception handler. */
@InternalSimpleDataLogsApi
public object CrashRecorder {
    /** The file one crash is saved to. */
    public const val FILE_NAME: String = "pending-crash.json"

    /** The most stack frames kept. */
    public const val MAX_STACK_FRAMES: Int = 40

    /** The most characters kept of the reason and the stack. */
    public const val MAX_TEXT_LENGTH: Int = 4_000

    /** Where the handler writes; null until installed. */
    @Volatile
    private var crashFile: File? = null

    /** Stamps the crash time. */
    @Volatile
    private var clock: Clock = Clock.SYSTEM

    /** True once our handler is in the chain. */
    private var isInstalled = false

    /** Installs the crash handler once, keeping the handler that was there before in the chain. */
    @Synchronized
    public fun install(stateDirectory: File, crashClock: Clock = Clock.SYSTEM) {
        // A second install only moves the crash file.
        crashFile = File(stateDirectory, FILE_NAME)
        clock = crashClock

        if (isInstalled) {
            return
        }

        isInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { crashedThread, uncaughtError ->
            handleUncaughtException(uncaughtError)
            previousHandler?.uncaughtException(crashedThread, uncaughtError)
        }
    }

    /** Saves the exception synchronously; the process is about to die, so nothing may wait. */
    public fun handleUncaughtException(uncaughtError: Throwable) {
        val targetFile = crashFile ?: return

        try {
            record(fromThrowable(uncaughtError, clock.currentTimeMillis()), targetFile)
        } catch (recordError: Throwable) {
            // The app is crashing anyway; never mask the original exception.
        }
    }

    /** Builds a bounded crash record from an exception. */
    public fun fromThrowable(uncaughtError: Throwable, occurredAtMillis: Long): PendingCrash {
        val stackFrames = uncaughtError.stackTrace.take(MAX_STACK_FRAMES).joinToString("\n") { stackFrame -> "at $stackFrame" }

        return PendingCrash(
            exceptionName = uncaughtError.javaClass.name,
            reason = uncaughtError.message.orEmpty().take(MAX_TEXT_LENGTH),
            stack = stackFrames.take(MAX_TEXT_LENGTH),
            occurredAtMillis = occurredAtMillis,
        )
    }

    /** Writes one crash to the file, bounded and synced. */
    public fun record(crash: PendingCrash, targetFile: File) {
        val crashJson = JsonWriter.encodeObject(
            linkedMapOf(
                "exception_name" to crash.exceptionName,
                "reason" to crash.reason.take(MAX_TEXT_LENGTH),
                "stack" to crash.stack.take(MAX_TEXT_LENGTH),
                "occurred_at_ms" to crash.occurredAtMillis,
            ),
        )

        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { crashStream ->
            crashStream.write(crashJson.toByteArray(Charsets.UTF_8))
            crashStream.fd.sync()
        }
    }

    /** Reads and deletes the crash a previous launch saved, if any. */
    public fun takePendingCrash(stateDirectory: File): PendingCrash? {
        val savedCrashFile = File(stateDirectory, FILE_NAME)
        if (!savedCrashFile.isFile) {
            return null
        }

        // Read it once, then delete it so it is never sent twice.
        val crashJson = try {
            savedCrashFile.readText(Charsets.UTF_8)
        } catch (readError: java.io.IOException) {
            null
        }
        savedCrashFile.delete()

        val crashFields = crashJson?.let { savedJson -> JsonReader.parseObjectOrNull(savedJson) } ?: return null

        return PendingCrash(
            exceptionName = crashFields["exception_name"] as? String ?: return null,
            reason = crashFields["reason"] as? String ?: "",
            stack = crashFields["stack"] as? String ?: "",
            occurredAtMillis = crashFields["occurred_at_ms"] as? Long ?: return null,
        )
    }
}
