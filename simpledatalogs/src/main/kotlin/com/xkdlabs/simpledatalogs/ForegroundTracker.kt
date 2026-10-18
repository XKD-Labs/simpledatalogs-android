package com.xkdlabs.simpledatalogs

/** Counts started activities to tell when the app enters and leaves the foreground, ignoring rotations. */
internal class ForegroundTracker {
    /** A change in the app's foreground state. */
    enum class Transition {
        /** The first activity started. */
        ENTERED_FOREGROUND,

        /** The last activity stopped, and not for a configuration change. */
        LEFT_FOREGROUND,

        /** Nothing changed. */
        NONE,
    }

    /** Activities started and not yet stopped. */
    private var startedActivityCount = 0

    /** True while the app counts as in the foreground. */
    var isInForeground: Boolean = false
        private set

    /** An activity started; returns ENTERED_FOREGROUND for the first one. */
    fun activityStarted(): Transition {
        startedActivityCount += 1

        if (isInForeground) {
            return Transition.NONE
        }

        isInForeground = true
        return Transition.ENTERED_FOREGROUND
    }

    /** An activity stopped; returns LEFT_FOREGROUND for the last one, unless it is only being recreated. */
    fun activityStopped(isChangingConfigurations: Boolean): Transition {
        startedActivityCount = maxOf(0, startedActivityCount - 1)

        // A rotation stops the activity and starts its replacement, which is not leaving the app.
        if (startedActivityCount > 0 || isChangingConfigurations || !isInForeground) {
            return Transition.NONE
        }

        isInForeground = false
        return Transition.LEFT_FOREGROUND
    }
}
