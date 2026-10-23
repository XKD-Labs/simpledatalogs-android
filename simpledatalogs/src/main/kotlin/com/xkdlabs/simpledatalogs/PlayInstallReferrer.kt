package com.xkdlabs.simpledatalogs

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import java.util.concurrent.atomic.AtomicBoolean

/** Reads the Play Store install referrer through Google's library, which the app adds itself. */
internal object PlayInstallReferrer {
    /** The library's entry class; absent when the app didn't add the dependency. */
    internal const val CLIENT_CLASS_NAME = "com.android.installreferrer.api.InstallReferrerClient"

    /** Connects to the Play Store, reads the referrer once and calls back; null on any failure. */
    fun read(context: Context, callback: InstallReferrerCallback) {
        if (!isLibraryPresent(PlayInstallReferrer::class.java.classLoader)) {
            AndroidSdkLogger.warning("installReferrer needs com.android.installreferrer:installreferrer in the app's dependencies")
            callback.onResult(null)
            return
        }

        val referrerClient = InstallReferrerClient.newBuilder(context.applicationContext ?: context).build()
        val hasAnswered = AtomicBoolean(false)

        // Answers exactly once, then closes the connection.
        val answer = { installReferrer: String? ->
            if (hasAnswered.compareAndSet(false, true)) {
                runCatching { referrerClient.endConnection() }
                callback.onResult(installReferrer)
            }
        }

        // Play answers asynchronously; a missing or old Play Store answers with an error code.
        val connectionResult = runCatching {
            referrerClient.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val installReferrer = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        runCatching { referrerClient.installReferrer.installReferrer }.getOrNull()
                    } else {
                        null
                    }

                    answer(installReferrer)
                }

                override fun onInstallReferrerServiceDisconnected() {
                    answer(null)
                }
            })
        }

        // Some devices refuse the binding outright.
        if (connectionResult.isFailure) {
            answer(null)
        }
    }

    /** True when Google's install referrer library is on the app's class path. */
    internal fun isLibraryPresent(classLoader: ClassLoader?): Boolean =
        runCatching { Class.forName(CLIENT_CLASS_NAME, false, classLoader) }.isSuccess
}
