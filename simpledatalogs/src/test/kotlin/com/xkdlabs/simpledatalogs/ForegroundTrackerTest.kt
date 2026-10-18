package com.xkdlabs.simpledatalogs

import com.xkdlabs.simpledatalogs.ForegroundTracker.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Foreground and background detection from activity starts and stops. */
class ForegroundTrackerTest {
    @Test
    fun firstStartEntersAndLastStopLeaves() {
        val tracker = ForegroundTracker()

        assertEquals(Transition.ENTERED_FOREGROUND, tracker.activityStarted())
        assertEquals(Transition.NONE, tracker.activityStarted())
        assertEquals(Transition.NONE, tracker.activityStopped(isChangingConfigurations = false))
        assertEquals(Transition.LEFT_FOREGROUND, tracker.activityStopped(isChangingConfigurations = false))
        assertFalse(tracker.isInForeground)
    }

    @Test
    fun aRotationIsNotABackgroundTrip() {
        val tracker = ForegroundTracker()
        tracker.activityStarted()

        // The rotating activity stops, then its replacement starts.
        assertEquals(Transition.NONE, tracker.activityStopped(isChangingConfigurations = true))
        assertEquals(Transition.NONE, tracker.activityStarted())
        assertEquals(Transition.LEFT_FOREGROUND, tracker.activityStopped(isChangingConfigurations = false))
    }

    @Test
    fun anUnmatchedStopDoesNothing() {
        val tracker = ForegroundTracker()

        assertEquals(Transition.NONE, tracker.activityStopped(isChangingConfigurations = false))
        assertEquals(Transition.ENTERED_FOREGROUND, tracker.activityStarted())
    }
}
