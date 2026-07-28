package com.motioncam

import com.google.common.truth.Truth.assertThat
import com.motioncam.upload.Retention
import org.junit.Test

class RetentionTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100L * day

    @Test
    fun deletesUploadedFilesOlderThanWindow() {
        val candidates = listOf(
            Retention.Candidate("old_uploaded", now - 8 * day, uploaded = true),
            Retention.Candidate("recent_uploaded", now - 2 * day, uploaded = true),
            Retention.Candidate("old_not_uploaded", now - 30 * day, uploaded = false)
        )
        val result = Retention.eligibleForDeletion(candidates, now, retentionDays = 7)
        assertThat(result.map { it.name }).containsExactly("old_uploaded")
    }

    @Test
    fun neverDeletesUnuploadedRegardlessOfAge() {
        val candidates = listOf(
            Retention.Candidate("ancient", now - 365 * day, uploaded = false)
        )
        assertThat(Retention.eligibleForDeletion(candidates, now, 7)).isEmpty()
    }

    @Test
    fun exactlyAtWindowBoundaryIsDeleted() {
        val candidates = listOf(
            Retention.Candidate("boundary", now - 7 * day, uploaded = true)
        )
        assertThat(Retention.eligibleForDeletion(candidates, now, 7).map { it.name })
            .containsExactly("boundary")
    }
}
