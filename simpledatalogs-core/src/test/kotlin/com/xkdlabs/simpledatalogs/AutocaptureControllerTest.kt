package com.xkdlabs.simpledatalogs

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Launches, sessions and crashes turned into events. */
class AutocaptureControllerTest {
    /** A fresh state folder per test. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** One emitted event. */
    data class EmittedEvent(val eventName: String, val properties: Map<String, Any?>, val occurredAtMillis: Long, val sessionId: String?)

    /** Records emitted events and the current session id. */
    private class RecordingEmitter : AutocaptureEmitter {
        /** Every event, in order. */
        val events = ArrayList<EmittedEvent>()

        /** The last session id set. */
        var currentSessionId: String? = null

        /** Records one event. */
        override fun emit(eventName: String, properties: Map<String, Any?>, occurredAtMillis: Long, sessionId: String?) {
            events.add(EmittedEvent(eventName, properties, occurredAtMillis, sessionId))
        }

        /** Records the session id. */
        override fun setSessionId(sessionId: String?) {
            currentSessionId = sessionId
        }
    }

    /** Runs file work inline, so tests stay synchronous. */
    private val inlineExecutor = Executor { task -> task.run() }

    /** Builds a controller over the shared state folder, as one process launch would. */
    private fun launch(options: Autocapture, emitter: RecordingEmitter): AutocaptureController {
        return AutocaptureController(options, temporaryFolder.root, emitter, inlineExecutor, ManualClock(), RecordingLogger())
    }

    @Test
    fun firstForegroundStartsASessionAndReportsTheFirstLaunch() {
        val emitter = RecordingEmitter()
        val controller = launch(Autocapture.SESSIONS + Autocapture.APP_LAUNCHES, emitter)

        controller.appBecameActive(1_000)
        controller.appWentToBackground(2_000)
        controller.appBecameActive(3_000)

        assertEquals(listOf("session_start", "app_launched"), emitter.events.map { event -> event.eventName })
        assertEquals(mapOf("is_first_launch" to true), emitter.events[1].properties)
        assertEquals(emitter.currentSessionId, emitter.events[1].sessionId)
        assertTrue(File(temporaryFolder.root, AutocaptureController.SESSION_FILE_NAME).exists())
    }

    @Test
    fun aRelaunchResumesARecentSessionAndEndsAStaleOne() {
        val firstEmitter = RecordingEmitter()
        val firstLaunch = launch(Autocapture.SESSIONS + Autocapture.APP_LAUNCHES, firstEmitter)
        firstLaunch.appBecameActive(0)
        firstLaunch.appWentToBackground(10_000)
        val firstSessionId = firstEmitter.currentSessionId

        // A relaunch within 30 minutes resumes the saved session.
        val secondEmitter = RecordingEmitter()
        launch(Autocapture.SESSIONS + Autocapture.APP_LAUNCHES, secondEmitter).appBecameActive(60_000)
        assertEquals(listOf("app_launched"), secondEmitter.events.map { event -> event.eventName })
        assertEquals(mapOf("is_first_launch" to false), secondEmitter.events.single().properties)
        assertEquals(firstSessionId, secondEmitter.currentSessionId)

        // A relaunch after 30 minutes away ends it at its last activity.
        val thirdEmitter = RecordingEmitter()
        launch(Autocapture.SESSIONS, thirdEmitter).appBecameActive(60_000 + SessionTracker.TIMEOUT_MILLIS)
        assertEquals(listOf("session_end", "session_start"), thirdEmitter.events.map { event -> event.eventName })
        assertEquals(EmittedEvent("session_end", mapOf("duration_s" to 60L), 60_000, firstSessionId), thirdEmitter.events[0])
    }

    @Test
    fun aSavedCrashIsSentOnTheNextLaunchStampedAtTheCrash() {
        val crash = CrashRecorder.fromThrowable(IllegalStateException("broken \"state\"\nline two"), 1_234)
        CrashRecorder.record(crash, File(temporaryFolder.root, CrashRecorder.FILE_NAME))

        val emitter = RecordingEmitter()
        launch(Autocapture.CRASHES, emitter).start()

        val crashEvent = emitter.events.single()
        assertEquals("app_crashed", crashEvent.eventName)
        assertEquals(1_234L, crashEvent.occurredAtMillis)
        assertEquals("java.lang.IllegalStateException", crashEvent.properties["exception_name"])
        assertEquals("broken \"state\"\nline two", crashEvent.properties["reason"])
        assertTrue((crashEvent.properties["stack"] as String).startsWith("at com.xkdlabs.simpledatalogs.AutocaptureControllerTest"))
        assertFalse(File(temporaryFolder.root, CrashRecorder.FILE_NAME).exists())
    }

    @Test
    fun crashRecordsAreBounded() {
        val deepError = RuntimeException("x".repeat(10_000))
        deepError.stackTrace = Array(500) { frameIndex -> StackTraceElement("com.example.Deep", "frame$frameIndex", "Deep.kt", frameIndex) }

        val crash = CrashRecorder.fromThrowable(deepError, 0)

        assertEquals(CrashRecorder.MAX_TEXT_LENGTH, crash.reason.length)
        assertEquals(CrashRecorder.MAX_STACK_FRAMES, crash.stack.lines().size)
    }

    @Test
    fun optionsCombineAndCompare() {
        val options = Autocapture.SESSIONS + Autocapture.CRASHES

        assertTrue(Autocapture.SESSIONS in options)
        assertFalse(Autocapture.APP_LAUNCHES in options)
        assertTrue(Autocapture.NONE.isEmpty)
        assertEquals(Autocapture.ALL, Autocapture.APP_LAUNCHES + Autocapture.SESSIONS + Autocapture.CRASHES + Autocapture.SCREEN_VIEWS)
        assertEquals("Autocapture(SESSIONS + CRASHES)", options.toString())
    }
}
