package com.xkdlabs.simpledatalogs

import java.util.logging.Level
import java.util.logging.Logger

/** Where the SDK writes its log lines; every failure is logged here and swallowed. */
@InternalSimpleDataLogsApi
public interface SdkLogger {
    /** Logs routine progress, such as a resumed send. */
    public fun info(message: String)

    /** Logs something the host may want to fix, such as a dropped property. */
    public fun warning(message: String)

    /** Logs a failure, such as a rejected key or an unwritable outbox. */
    public fun error(message: String, throwable: Throwable? = null)
}

/** The JVM default: java.util.logging under the `simpledatalogs` logger name. */
@InternalSimpleDataLogsApi
public object JavaUtilSdkLogger : SdkLogger {
    /** The logger every line goes to. */
    private val logger: Logger = Logger.getLogger(LOG_TAG)

    /** Logs at INFO. */
    override fun info(message: String) {
        logger.log(Level.INFO, message)
    }

    /** Logs at WARNING. */
    override fun warning(message: String) {
        logger.log(Level.WARNING, message)
    }

    /** Logs at SEVERE, with the cause when there is one. */
    override fun error(message: String, throwable: Throwable?) {
        logger.log(Level.SEVERE, message, throwable)
    }
}

/** The tag (Android) and logger name (JVM) of every SDK log line. */
public const val LOG_TAG: String = "simpledatalogs"
