package com.xkdlabs.simpledatalogs

/** What `configure` captures without any `track` call; combine options with `+`, e.g. `Autocapture.SESSIONS + Autocapture.CRASHES`. */
public class Autocapture private constructor(
    /** The option bits. */
    private val flags: Int,
) {
    /** The options. */
    public companion object {
        /** Nothing: the default. */
        @JvmField
        public val NONE: Autocapture = Autocapture(0)

        /** `app_launched` once per process start that shows UI, with `is_first_launch` on the very first. */
        @JvmField
        public val APP_LAUNCHES: Autocapture = Autocapture(1 shl 0)

        /** `session_start`, `session_end` and a `session_id` on every event; a session ends after 30 minutes away. */
        @JvmField
        public val SESSIONS: Autocapture = Autocapture(1 shl 1)

        /** Uncaught exceptions, saved at the crash and sent as `app_crashed` on the next launch. */
        @JvmField
        public val CRASHES: Autocapture = Autocapture(1 shl 2)

        /** Android only: `screen_view` with `screen` = the activity's simple name each time an activity resumes. */
        @JvmField
        public val SCREEN_VIEWS: Autocapture = Autocapture(1 shl 3)

        /** Everything: the one-line opt-in. */
        @JvmField
        public val ALL: Autocapture = Autocapture((1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 3))
    }

    /** Both sets of options together. */
    public operator fun plus(other: Autocapture): Autocapture {
        return Autocapture(flags or other.flags)
    }

    /** True when every option in `option` is on. */
    public operator fun contains(option: Autocapture): Boolean {
        return flags and option.flags == option.flags
    }

    /** True when no option is on. */
    public val isEmpty: Boolean
        get() = flags == 0

    /** Equal when the same options are on. */
    override fun equals(other: Any?): Boolean {
        return other is Autocapture && other.flags == flags
    }

    /** Hashes the option bits. */
    override fun hashCode(): Int {
        return flags
    }

    /** Lists the options that are on. */
    override fun toString(): String {
        val optionNames = listOf("APP_LAUNCHES", "SESSIONS", "CRASHES", "SCREEN_VIEWS").filterIndexed { optionIndex, _ -> flags and (1 shl optionIndex) != 0 }

        return "Autocapture(" + optionNames.joinToString(" + ").ifEmpty { "NONE" } + ")"
    }
}
