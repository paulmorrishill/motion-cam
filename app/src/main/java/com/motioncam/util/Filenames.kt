package com.motioncam.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds recording file names of the form:
 *   <deviceName>_dd-MM-yyyy-HH-mm-ss.mp4
 * using the UK date format requested (day-month-year, 24h clock, seconds).
 *
 * Kept as pure functions so the format is unit-testable without Android.
 */
object Filenames {

    private const val PATTERN = "dd-MM-yyyy-HH-mm-ss"

    /** Sanitises a user supplied device name so it is safe as a file name segment. */
    fun sanitizeDeviceName(raw: String): String {
        val trimmed = raw.trim()
        val cleaned = trimmed.replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-')
        return if (cleaned.isEmpty()) "device" else cleaned
    }

    /** Returns just the timestamp portion for the given epoch millis. */
    fun timestamp(epochMillis: Long): String {
        val fmt = SimpleDateFormat(PATTERN, Locale.UK)
        return fmt.format(Date(epochMillis))
    }

    /** Full base name (no extension). */
    fun baseName(deviceName: String, epochMillis: Long): String {
        return "${sanitizeDeviceName(deviceName)}_${timestamp(epochMillis)}"
    }

    /** Full file name with the mp4 extension. */
    fun videoFileName(deviceName: String, epochMillis: Long): String {
        return "${baseName(deviceName, epochMillis)}.mp4"
    }
}
