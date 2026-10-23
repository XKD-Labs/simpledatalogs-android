package com.xkdlabs.simpledatalogs

import org.junit.Assert.assertEquals
import org.junit.Test

/** How long an app's partial batch waits: shorter while debuggable, so new events show within seconds. */
class BatchIntervalTest {
    @Test
    fun debuggableAppsWaitFiveSecondsAndReleaseAppsTen() {
        assertEquals(5_000L, SimpleDataLogs.appBatchIntervalMillis(isDebuggable = true))
        assertEquals(10_000L, SimpleDataLogs.appBatchIntervalMillis(isDebuggable = false))
    }
}
