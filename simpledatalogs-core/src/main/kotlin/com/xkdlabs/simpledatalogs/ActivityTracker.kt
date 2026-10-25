package com.xkdlabs.simpledatalogs

/** Whether events say if someone was using the app (`app_state`) and for how long (`engaged_ms`). */
public enum class ActivityReporting {
    /** Every event tracked now carries `app_state`, and `engaged_ms` while the app is in front. */
    AUTOMATIC,

    /** Events carry neither; the server counts every session as active. */
    OFF,
}

/** The app's foreground time between events: active and background moments in, one stamp per event out; not thread-safe on its own. */
internal class ActivityTracker(isActive: Boolean, atMillis: Long) {
    /** True while the app is in front. */
    private var isInFront = isActive

    /** When the current foreground stretch started, or the last stamp inside it. */
    private var activeSinceMillis: Long? = if (isActive) atMillis else null

    /** Foreground time from stretches that ended since the last stamp. */
    private var finishedActiveMillis = 0L

    /** The app came to the front, or left it. */
    fun setActive(isActive: Boolean, atMillis: Long) {
        if (isActive == isInFront) {
            return
        }

        // Leaving keeps the stretch for the next stamp; coming back starts a new one.
        if (isInFront) {
            finishedActiveMillis += currentActiveMillis(atMillis)
        }

        isInFront = isActive
        activeSinceMillis = if (isActive) atMillis else null
    }

    /** The props for one event tracked now, counting afresh from it. */
    fun stamp(atMillis: Long): Map<String, Any> {
        val engagedMillis = minOf(finishedActiveMillis + currentActiveMillis(atMillis), MAX_ENGAGED_MILLIS)
        finishedActiveMillis = 0
        activeSinceMillis = if (isInFront) atMillis else null

        return if (isInFront) {
            mapOf(APP_STATE to "active", ENGAGED_MS to engagedMillis)
        } else {
            mapOf(APP_STATE to "background")
        }
    }

    /** The current stretch's length up to now, never negative. */
    private fun currentActiveMillis(atMillis: Long): Long {
        val startedAtMillis = activeSinceMillis ?: return 0
        return maxOf(atMillis - startedAtMillis, 0)
    }

    internal companion object {
        /** The longest engaged time one event reports: a day, as the server accepts. */
        const val MAX_ENGAGED_MILLIS = 86_400_000L

        /** The prop names the server reads. */
        const val APP_STATE = "app_state"
        const val ENGAGED_MS = "engaged_ms"
    }
}
