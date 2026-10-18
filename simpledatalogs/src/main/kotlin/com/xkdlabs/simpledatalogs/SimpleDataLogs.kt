package com.xkdlabs.simpledatalogs

import android.app.Application
import android.content.Context
import android.os.Looper
import java.io.File
import java.util.concurrent.Executor

/** The Android entry point: configure once in `Application.onCreate`, then track events from any thread. */
public object SimpleDataLogs {
    /** This SDK's version, sent in every User-Agent. */
    public const val SDK_VERSION: String = SimpleDataLogsClient.SDK_VERSION

    /** The production ingest host. */
    public const val DEFAULT_ENDPOINT: String = SimpleDataLogsClient.DEFAULT_ENDPOINT

    /** The outbox folder's name inside `noBackupFilesDir`. */
    internal const val STORAGE_FOLDER_NAME = "simpledatalogs"

    /** The User-Agent product name of this SDK. */
    internal const val USER_AGENT_NAME = "simpledatalogs-android"

    /** Guards the settings below and the single configure. */
    private val facadeLock = Any()

    /** The running client, set once by `configure`. */
    @Volatile
    private var client: SimpleDataLogsClient? = null

    /** The device id set before `configure`. */
    private var deviceIdBeforeConfigure: String? = null

    /** The default properties set before `configure`. */
    private var defaultPropertiesBeforeConfigure: Map<String, Any?> = emptyMap()

    /** The opt-out state set before `configure`. */
    private var isEnabledBeforeConfigure = true

    /** The running autocapture, if any was asked for. */
    private var autocaptureController: AutocaptureController? = null

    /** Starts the SDK; call once in `Application.onCreate`, after `setEnabled` if the user may have opted out. Later calls are ignored. */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, ingestKey: String, endpoint: String = DEFAULT_ENDPOINT, autocapture: Autocapture = Autocapture.NONE) {
        // Trim copy-paste whitespace, and warn about a key that cannot work in an app.
        val trimmedIngestKey = ingestKey.trim()
        warnAboutSuspiciousKey(trimmedIngestKey)

        val applicationContext = context.applicationContext ?: context

        synchronized(facadeLock) {
            if (client != null) {
                AndroidSdkLogger.warning("configure was called again; the first configuration stays in effect")
                return
            }

            // The outbox lives where Auto Backup never copies it.
            val storageDirectory = File(applicationContext.noBackupFilesDir, STORAGE_FOLDER_NAME)
            val configuration = ClientConfiguration(
                ingestKey = trimmedIngestKey,
                storageDirectory = storageDirectory,
                endpoint = endpoint,
                userAgent = "$USER_AGENT_NAME/$SDK_VERSION",
                batchIntervalMillis = SimpleDataLogsClient.APP_BATCH_INTERVAL_MILLIS,
            )

            // Build the client with whatever the app set before configure.
            val newClient = SimpleDataLogsClient(configuration, ClientDependencies(logger = AndroidSdkLogger), isEnabledBeforeConfigure)
            newClient.setDeviceId(deviceIdBeforeConfigure)
            newClient.setDefaultProperties(defaultPropertiesBeforeConfigure)
            client = newClient

            // Opt-in autocapture: launches, sessions, crashes and screen views, as asked.
            val controller = startAutocapture(autocapture, storageDirectory, newClient)
            autocaptureController = controller

            // Flush when the app leaves the foreground; feed sessions and screen views.
            observeLifecycle(applicationContext, autocapture, newClient, controller)
        }
    }

    /** Records one event; it returns at once and never throws. Properties may be strings, numbers or booleans. */
    @JvmStatic
    @JvmOverloads
    public fun track(eventName: String, properties: Map<String, Any?> = emptyMap()) {
        val runningClient = client
        if (runningClient == null) {
            AndroidSdkLogger.error("track($eventName) was called before configure; event dropped")
            return
        }

        runningClient.track(eventName, properties)
    }

    /** Records a screen view: `screen_view` with a `screen` prop. */
    @JvmStatic
    @JvmOverloads
    public fun trackScreen(screenName: String, properties: Map<String, Any?> = emptyMap()) {
        val runningClient = client
        if (runningClient == null) {
            AndroidSdkLogger.error("trackScreen($screenName) was called before configure; event dropped")
            return
        }

        runningClient.trackScreen(screenName, properties)
    }

    /** Sets the anonymous device id on every later event, or clears it with null. */
    @JvmStatic
    public fun setDeviceId(deviceId: String?) {
        synchronized(facadeLock) {
            deviceIdBeforeConfigure = deviceId
            client?.setDeviceId(deviceId)
        }
    }

    /** Replaces the properties merged into every later event, e.g. app_version or distribution. */
    @JvmStatic
    public fun setDefaultProperties(properties: Map<String, Any?>) {
        synchronized(facadeLock) {
            defaultPropertiesBeforeConfigure = properties.toMap()
            client?.setDefaultProperties(properties)
        }
    }

    /** The opt-out: false ignores new events and deletes every unsent event; call it before `configure` on every launch. */
    @JvmStatic
    public fun setEnabled(isEnabled: Boolean) {
        synchronized(facadeLock) {
            isEnabledBeforeConfigure = isEnabled
            client?.setEnabled(isEnabled)
        }
    }

    /** Seals and starts sending everything tracked so far, without waiting. */
    @JvmStatic
    public fun flush() {
        client?.flush()
    }

    /** Sends everything now, waiting up to `timeoutMillis`; true when nothing is left to send. For background work, never the main thread. */
    @JvmStatic
    public fun flushBlocking(timeoutMillis: Long): Boolean {
        val runningClient = client
        if (runningClient == null) {
            AndroidSdkLogger.error("flushBlocking was called before configure")
            return false
        }

        // Blocking the main thread risks an ANR, so it is refused there.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AndroidSdkLogger.error("flushBlocking was called on the main thread; use flush() there")
            return false
        }

        return runningClient.flushBlocking(timeoutMillis)
    }

    // Logs an empty key, or a secret key that belongs on servers only.
    private fun warnAboutSuspiciousKey(ingestKey: String) {
        if (ingestKey.isEmpty()) {
            AndroidSdkLogger.error("configure was given an empty key; ingest will answer 401")
        } else if (ingestKey.startsWith("sdl_sec_")) {
            AndroidSdkLogger.warning("A secret sdl_sec_ key was configured; apps should ship a public sdl_pub_ key")
        }
    }

    // Starts the autocapture controller when any option is on.
    private fun startAutocapture(options: Autocapture, storageDirectory: File, runningClient: SimpleDataLogsClient): AutocaptureController? {
        if (options.isEmpty) {
            return null
        }

        // Autocaptured events go through the client; file work runs on its worker thread.
        val emitter = object : AutocaptureEmitter {
            override fun emit(eventName: String, properties: Map<String, Any?>, occurredAtMillis: Long, sessionId: String?) {
                runningClient.trackAt(eventName, properties, occurredAtMillis, sessionId)
            }

            override fun setSessionId(sessionId: String?) {
                runningClient.setSessionId(sessionId)
            }
        }
        val workerExecutor = Executor { task -> runningClient.executeInBackground(task) }

        val controller = AutocaptureController(options, storageDirectory, emitter, workerExecutor, Clock.SYSTEM, AndroidSdkLogger)
        controller.start()

        return controller
    }

    // Registers the activity callbacks that flush on background and feed autocapture.
    private fun observeLifecycle(applicationContext: Context, options: Autocapture, runningClient: SimpleDataLogsClient, controller: AutocaptureController?) {
        val application = applicationContext as? Application
        if (application == null) {
            AndroidSdkLogger.warning("configure got a context without an Application; background flushes and autocapture are off")
            return
        }

        // Screen views are sent only when asked for.
        val screenViewHandler: ((String) -> Unit)? = if (Autocapture.SCREEN_VIEWS in options) {
            { screenName -> runningClient.trackScreen(screenName) }
        } else {
            null
        }

        val observer = AppLifecycleObserver(
            onEnteredForeground = { controller?.appBecameActive(System.currentTimeMillis()) },
            onLeftForeground = {
                controller?.appWentToBackground(System.currentTimeMillis())
                runningClient.flush()
            },
            onScreenResumed = screenViewHandler,
        )

        application.registerActivityLifecycleCallbacks(observer)
    }
}
