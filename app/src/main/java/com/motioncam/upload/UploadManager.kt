package com.motioncam.upload

import android.content.Context
import com.motioncam.service.AppState
import com.motioncam.settings.SettingsStore
import com.motioncam.util.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Owns the FTP upload queue, upload-history persistence and the weekly
 * retention purge. Runs inside the camera service's coroutine scope.
 *
 * Flow:
 *  - [trigger] is called after each recording is finalised (and on start).
 *  - A single consumer coroutine scans the recordings directory, uploads any
 *    not-yet-uploaded files (oldest first) and, after each pass, deletes
 *    already-uploaded files older than the retention window.
 */
class UploadManager(
    private val context: Context,
    private val recordingsDir: File,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val uploadedMeta =
        context.getSharedPreferences("uploads_meta", Context.MODE_PRIVATE)
    private val uploader = FtpUploader()
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val passLock = Mutex()
    private val recent = ArrayDeque<RecentFile>()

    fun start() {
        scope.launch {
            for (ignored in triggers) {
                runCatching { processPass() }
            }
        }
        trigger()
    }

    fun trigger() {
        triggers.trySend(Unit)
    }

    private suspend fun processPass() = passLock.withLock {
        val cfg = settings.current
        val activeName = AppState.snapshot().currentFileName

        val all = recordingsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".mp4")
        }?.toList().orEmpty()

        // Files that still need uploading: not the active recording, not recently
        // written, and not already recorded as uploaded.
        val now = clock()
        val pending = all
            .filter { it.name != activeName }
            .filter { now - it.lastModified() > 5_000 }
            .filter { uploadedAt(it.name) == null }
            .sortedBy { it.lastModified() }

        // Publish the queue immediately so the UI shows what is waiting.
        publishQueue(pending.map { UploadItem(it.name, it.length(), 0L, UploadStatus.QUEUED) })

        if (cfg.ftpConfigured) {
            val ftpConfig = FtpUploader.Config(
                host = cfg.ftpHost,
                port = cfg.ftpPort,
                user = cfg.ftpUser,
                password = cfg.ftpPassword,
                remoteDir = cfg.ftpPath
            )
            for (file in pending) {
                setItemStatus(file.name, UploadStatus.UPLOADING, 0L, null)
                val result = uploader.upload(file, ftpConfig) { sent ->
                    setItemStatus(file.name, UploadStatus.UPLOADING, sent, null)
                }
                when (result) {
                    is FtpUploader.Result.Success -> {
                        markUploaded(file.name, now)
                        setItemStatus(file.name, UploadStatus.DONE, file.length(), null)
                        addRecent(RecentFile(file.name, RecentFile.Event.UPLOADED, now, file.length()))
                        L.i("Upload", "uploaded ${file.name} (${file.length() / 1024}KB)")
                    }
                    is FtpUploader.Result.Failure -> {
                        setItemStatus(file.name, UploadStatus.FAILED, 0L, result.message)
                        L.w("Upload", "failed ${file.name}: ${result.message}")
                    }
                }
            }
        }

        // Drop completed items from the visible queue, keep failures/pending.
        AppState.update { s ->
            s.copy(uploadQueue = s.uploadQueue.filter { it.status != UploadStatus.DONE })
        }

        runRetention(cfg.retentionDays, now)
    }

    private fun runRetention(retentionDays: Int, now: Long) {
        val candidates = recordingsDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.map {
                Retention.Candidate(
                    name = it.name,
                    referenceMillis = it.lastModified(),
                    uploaded = uploadedAt(it.name) != null
                )
            }.orEmpty()

        val toDelete = Retention.eligibleForDeletion(candidates, now, retentionDays)
        for (c in toDelete) {
            val f = File(recordingsDir, c.name)
            val size = f.length()
            if (f.delete()) {
                clearUploaded(c.name)
                addRecent(RecentFile(c.name, RecentFile.Event.DELETED, now, size))
                L.i("Retention", "deleted uploaded file older than ${retentionDays}d: ${c.name}")
            }
        }
    }

    // ---- upload metadata persistence ----

    private fun uploadedAt(name: String): Long? {
        val v = uploadedMeta.getLong(name, -1L)
        return if (v < 0) null else v
    }

    private fun markUploaded(name: String, whenMs: Long) {
        uploadedMeta.edit().putLong(name, whenMs).apply()
    }

    private fun clearUploaded(name: String) {
        uploadedMeta.edit().remove(name).apply()
    }

    // ---- AppState plumbing ----

    private fun publishQueue(items: List<UploadItem>) {
        AppState.update { it.copy(uploadQueue = items) }
    }

    private fun setItemStatus(name: String, status: UploadStatus, sent: Long, error: String?) {
        AppState.update { s ->
            val existing = s.uploadQueue.firstOrNull { it.name == name }
            val updated = if (existing != null) {
                s.uploadQueue.map {
                    if (it.name == name) it.copy(uploadedBytes = sent, status = status, error = error)
                    else it
                }
            } else {
                s.uploadQueue + UploadItem(name, File(recordingsDir, name).length(), sent, status, error)
            }
            s.copy(uploadQueue = updated)
        }
    }

    private fun addRecent(file: RecentFile) {
        recent.addFirst(file)
        while (recent.size > 30) recent.removeLast()
        AppState.update { it.copy(recentFiles = recent.toList()) }
    }
}
