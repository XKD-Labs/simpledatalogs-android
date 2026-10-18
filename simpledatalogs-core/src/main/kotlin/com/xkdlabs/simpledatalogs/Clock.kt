package com.xkdlabs.simpledatalogs

/** The wall clock the SDK stamps events with; tests inject a fixed one. */
@InternalSimpleDataLogsApi
public fun interface Clock {
    /** Milliseconds since 1970-01-01T00:00:00Z. */
    public fun currentTimeMillis(): Long

    /** Holds the system clock. */
    public companion object {
        /** The real wall clock. */
        public val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

/** A cancellable delayed action, such as a retry or a batch timer. */
@InternalSimpleDataLogsApi
public interface ScheduledTimer {
    /** Stops the action from running, if it has not run yet. */
    public fun cancel()
}

/** Runs an action after a delay; tests inject one that fires on demand instead of sleeping. */
@InternalSimpleDataLogsApi
public interface TimerScheduler {
    /** Runs `action` once, `delayMillis` from now, on any thread. */
    public fun schedule(delayMillis: Long, action: Runnable): ScheduledTimer
}
