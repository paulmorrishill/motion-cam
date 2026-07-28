package com.motioncam.camera

import java.io.File

/**
 * Tracks the current and next-pending recording files across seamless size
 * rollovers ([android.media.MediaRecorder.setNextOutputFile]). Kept free of any
 * Android/MediaRecorder dependency so the file-tracking logic — the part most
 * prone to off-by-one bugs when a 2GB file rolls over — is unit-testable.
 *
 * Usage from the recorder's OnInfoListener:
 *   MAX_FILESIZE_APPROACHING       -> setNextOutputFile(onApproaching())
 *   NEXT_OUTPUT_FILE_STARTED       -> onNextStarted() gives (completed, newActive)
 */
class RolloverCoordinator(private val nextFileProvider: () -> File) {

    var currentFile: File? = null
        private set
    var pendingFile: File? = null
        private set

    /** Called when recording starts with the first output file. */
    fun begin(first: File) {
        currentFile = first
        pendingFile = null
    }

    /**
     * The max size is approaching: allocate the next segment file and remember it
     * as pending. Returns the file to hand to setNextOutputFile.
     */
    fun onApproaching(): File {
        val next = nextFileProvider()
        pendingFile = next
        return next
    }

    /**
     * The recorder has switched to the pending file. Returns
     * (completedFile, newActiveFile): the just-finished segment and the file now
     * being written. Either may be null if state was unexpected.
     */
    fun onNextStarted(): Pair<File?, File?> {
        val completed = currentFile
        currentFile = pendingFile
        pendingFile = null
        return completed to currentFile
    }

    fun reset() {
        currentFile = null
        pendingFile = null
    }
}
