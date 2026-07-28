package com.motioncam

import com.google.common.truth.Truth.assertThat
import com.motioncam.util.Filenames
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FilenamesTest {

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(y, mo - 1, d, h, mi, s)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun timestamp_usesUkDayMonthYearOrder() {
        val t = Filenames.timestamp(epoch(2026, 7, 28, 9, 5, 3))
        assertThat(t).isEqualTo("28-07-2026-09-05-03")
    }

    @Test
    fun videoFileName_combinesDeviceAndTimestamp() {
        val name = Filenames.videoFileName("garage", epoch(2026, 12, 1, 23, 59, 59))
        assertThat(name).isEqualTo("garage_01-12-2026-23-59-59.mp4")
    }

    @Test
    fun sanitize_replacesUnsafeCharacters() {
        assertThat(Filenames.sanitizeDeviceName("Front Door!")).isEqualTo("Front-Door")
        assertThat(Filenames.sanitizeDeviceName("  ")).isEqualTo("device")
        assertThat(Filenames.sanitizeDeviceName("cam/01")).isEqualTo("cam-01")
    }
}
