package com.motioncam

import com.google.common.truth.Truth.assertThat
import com.motioncam.camera.RolloverCoordinator
import org.junit.Test
import java.io.File

class RolloverCoordinatorTest {

    private var counter = 0
    private fun coordinator() = RolloverCoordinator { File("/tmp/seg-${++counter}.mp4") }

    @Test
    fun beginSetsCurrentFile() {
        val c = coordinator()
        val first = File("/tmp/first.mp4")
        c.begin(first)
        assertThat(c.currentFile).isEqualTo(first)
        assertThat(c.pendingFile).isNull()
    }

    @Test
    fun approachingAllocatesAndRemembersNextFile() {
        val c = coordinator()
        c.begin(File("/tmp/first.mp4"))
        val next = c.onApproaching()
        assertThat(c.pendingFile).isEqualTo(next)
        // Current file is unchanged until the rollover actually starts.
        assertThat(c.currentFile).isEqualTo(File("/tmp/first.mp4"))
    }

    @Test
    fun nextStartedAdvancesActiveAndReportsCompleted() {
        val c = coordinator()
        val first = File("/tmp/first.mp4")
        c.begin(first)
        val next = c.onApproaching()

        val (completed, active) = c.onNextStarted()
        assertThat(completed).isEqualTo(first)   // the finished segment
        assertThat(active).isEqualTo(next)       // now recording into the next file
        assertThat(c.currentFile).isEqualTo(next)
        assertThat(c.pendingFile).isNull()
    }

    @Test
    fun multipleRolloversTrackFilesInOrderWithNoDuplicatesOrGaps() {
        val c = coordinator()
        val first = File("/tmp/first.mp4")
        c.begin(first)

        val completedFiles = mutableListOf<File>()
        var expectedActive = first
        repeat(4) {
            val next = c.onApproaching()
            val (completed, active) = c.onNextStarted()
            assertThat(completed).isEqualTo(expectedActive) // each prior segment completes exactly once
            assertThat(active).isEqualTo(next)
            completed?.let { completedFiles.add(it) }
            expectedActive = next
        }

        // Four rollovers -> four completed segments, all distinct, in order.
        assertThat(completedFiles).hasSize(4)
        assertThat(completedFiles.toSet()).hasSize(4)
        assertThat(c.currentFile).isEqualTo(expectedActive)
    }

    @Test
    fun resetClearsState() {
        val c = coordinator()
        c.begin(File("/tmp/first.mp4"))
        c.onApproaching()
        c.reset()
        assertThat(c.currentFile).isNull()
        assertThat(c.pendingFile).isNull()
    }
}
