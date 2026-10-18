package com.xkdlabs.simpledatalogs

import android.util.Log

/** Writes SDK log lines to Logcat under the `simpledatalogs` tag. */
internal object AndroidSdkLogger : SdkLogger {
    /** Logs at INFO. */
    override fun info(message: String) {
        Log.i(LOG_TAG, message)
    }

    /** Logs at WARN. */
    override fun warning(message: String) {
        Log.w(LOG_TAG, message)
    }

    /** Logs at ERROR, with the cause when there is one. */
    override fun error(message: String, throwable: Throwable?) {
        Log.e(LOG_TAG, message, throwable)
    }
}
