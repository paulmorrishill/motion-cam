package com.motioncam.upload

enum class UploadStatus { QUEUED, UPLOADING, DONE, FAILED }

/** One file in the upload queue / progress screen. */
data class UploadItem(
    val name: String,
    val sizeBytes: Long,
    val uploadedBytes: Long = 0L,
    val status: UploadStatus = UploadStatus.QUEUED,
    val error: String? = null
) {
    val progress: Float
        get() = if (sizeBytes <= 0) 0f else (uploadedBytes.toFloat() / sizeBytes).coerceIn(0f, 1f)
}

/** An entry in the "recent files" history (uploaded or deleted). */
data class RecentFile(
    val name: String,
    val event: Event,
    val timeMillis: Long,
    val sizeBytes: Long = 0L
) {
    enum class Event { UPLOADED, DELETED }
}
