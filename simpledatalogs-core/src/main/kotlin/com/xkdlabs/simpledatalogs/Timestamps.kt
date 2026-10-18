package com.xkdlabs.simpledatalogs

/** Formats `ts` values: ISO 8601 in UTC with millisecond precision, without java.time (Android minSdk 21). */
internal object Timestamps {
    /** Milliseconds in one day. */
    private const val MILLIS_PER_DAY = 86_400_000L

    /** Formats epoch milliseconds as e.g. `2026-10-12T09:14:03.120Z`. */
    fun formatIso8601Utc(epochMillis: Long): String {
        // Split into whole days and the time of day, flooring so dates before 1970 work too.
        val daysSinceEpoch = epochMillis.floorDiv(MILLIS_PER_DAY)
        val millisOfDay = epochMillis.mod(MILLIS_PER_DAY)

        // Turn the day count into a calendar date.
        val (year, month, dayOfMonth) = civilDateFromDays(daysSinceEpoch)

        // Break the time of day into its fields.
        val hour = millisOfDay / 3_600_000L
        val minute = millisOfDay / 60_000L % 60
        val second = millisOfDay / 1_000L % 60
        val millisecond = millisOfDay % 1_000L

        return buildString(24) {
            append(padded(year.toLong(), 4)).append('-')
            append(padded(month.toLong(), 2)).append('-')
            append(padded(dayOfMonth.toLong(), 2)).append('T')
            append(padded(hour, 2)).append(':')
            append(padded(minute, 2)).append(':')
            append(padded(second, 2)).append('.')
            append(padded(millisecond, 3)).append('Z')
        }
    }

    // Converts days since 1970-01-01 to (year, month, day), using Howard Hinnant's civil_from_days.
    private fun civilDateFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
        // Shift the epoch to 0000-03-01, so leap days fall at the end of each year.
        val shiftedDays = daysSinceEpoch + 719_468
        val era = shiftedDays.floorDiv(146_097L)
        val dayOfEra = shiftedDays - era * 146_097

        // Find the year of the era, then the day of that March-based year.
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)

        // Map the March-based month back to January-based.
        val marchBasedMonth = (5 * dayOfYear + 2) / 153
        val dayOfMonth = dayOfYear - (153 * marchBasedMonth + 2) / 5 + 1
        val month = if (marchBasedMonth < 10) marchBasedMonth + 3 else marchBasedMonth - 9
        val year = yearOfEra + era * 400 + (if (month <= 2) 1 else 0)

        return Triple(year.toInt(), month.toInt(), dayOfMonth.toInt())
    }

    // Left-pads a non-negative number with zeros to the given width.
    private fun padded(value: Long, width: Int): String {
        return value.toString().padStart(width, '0')
    }
}
