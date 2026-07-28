package com.motioncam.upload

/**
 * Pure retention policy: an uploaded file older than the retention window is
 * eligible for deletion. Kept side-effect free for unit testing.
 */
object Retention {

    data class Candidate(
        val name: String,
        /** Reference time for age (recording time / last-modified), epoch millis. */
        val referenceMillis: Long,
        val uploaded: Boolean
    )

    fun eligibleForDeletion(
        candidates: List<Candidate>,
        nowMillis: Long,
        retentionDays: Int
    ): List<Candidate> {
        val windowMs = retentionDays.toLong() * 24L * 60L * 60L * 1000L
        return candidates.filter { it.uploaded && (nowMillis - it.referenceMillis) >= windowMs }
    }
}
