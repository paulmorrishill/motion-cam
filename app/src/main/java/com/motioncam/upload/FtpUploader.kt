package com.motioncam.upload

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Uploads a single file to an unsecured (plain) FTP server on the local network.
 * Reports byte progress and verifies the remote size after transfer.
 */
class FtpUploader {

    data class Config(
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        val remoteDir: String
    )

    sealed class Result {
        object Success : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Verify the FTP settings without uploading a real recording: connect, log in,
     * ensure the remote dir exists and confirm it is writable by storing then
     * deleting a tiny probe file. Returns [Result.Success] if all steps pass.
     */
    suspend fun test(config: Config): Result = withContext(Dispatchers.IO) {
        val ftp = FTPClient()
        ftp.connectTimeout = 15_000
        ftp.setDataTimeout(java.time.Duration.ofSeconds(30))
        try {
            ftp.connect(config.host, config.port)
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                ftp.disconnect()
                return@withContext Result.Failure("Connect refused (${ftp.replyCode})")
            }
            if (!ftp.login(config.user, config.password)) {
                ftp.disconnect()
                return@withContext Result.Failure("Login failed")
            }
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.enterLocalPassiveMode()
            ensureRemoteDir(ftp, config.remoteDir)

            // Write + delete a small probe to prove the directory is writable.
            val probe = config.remoteDir.trimEnd('/') + "/.motioncam_test"
            val wrote = "ok".byteInputStream().use { ftp.storeFile(probe, it) }
            if (!wrote) {
                return@withContext Result.Failure(
                    "Cannot write to ${config.remoteDir} (${ftp.replyString?.trim()})"
                )
            }
            ftp.deleteFile(probe)
            Result.Success
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                if (ftp.isConnected) {
                    ftp.logout()
                    ftp.disconnect()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * @param onProgress called with bytes-transferred as the upload proceeds.
     */
    suspend fun upload(
        file: File,
        config: Config,
        onProgress: (Long) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext Result.Failure("File missing: ${file.name}")
        val ftp = FTPClient()
        ftp.connectTimeout = 15_000
        ftp.setDataTimeout(java.time.Duration.ofSeconds(30))
        try {
            ftp.connect(config.host, config.port)
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                ftp.disconnect()
                return@withContext Result.Failure("Connect refused (${ftp.replyCode})")
            }
            if (!ftp.login(config.user, config.password)) {
                ftp.disconnect()
                return@withContext Result.Failure("Login failed")
            }
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.enterLocalPassiveMode()

            ensureRemoteDir(ftp, config.remoteDir)

            val remoteName = config.remoteDir.trimEnd('/') + "/" + file.name
            val out: OutputStream = ftp.storeFileStream(remoteName)
                ?: return@withContext Result.Failure("Server refused STOR (${ftp.replyString?.trim()})")

            var transferred = 0L
            val scope: CoroutineScope = CoroutineScope(coroutineContext)
            BufferedInputStream(file.inputStream()).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    scope.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    transferred += n
                    onProgress(transferred)
                }
                out.flush()
            }
            out.close()

            if (!ftp.completePendingCommand()) {
                return@withContext Result.Failure("Transfer incomplete (${ftp.replyString?.trim()})")
            }

            // Verify: remote size should match local length when the server reports it.
            val remoteSize = try {
                ftp.listFiles(remoteName).firstOrNull()?.size ?: -1L
            } catch (e: Exception) {
                -1L
            }
            if (remoteSize in 0 until file.length()) {
                return@withContext Result.Failure(
                    "Size mismatch: local=${file.length()} remote=$remoteSize"
                )
            }
            Result.Success
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                if (ftp.isConnected) {
                    ftp.logout()
                    ftp.disconnect()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Creates each path segment of [dir] if absent, then changes into it. */
    private fun ensureRemoteDir(ftp: FTPClient, dir: String) {
        val clean = dir.trim().trim('/')
        if (clean.isEmpty()) return
        var path = ""
        for (segment in clean.split('/')) {
            if (segment.isEmpty()) continue
            path += "/$segment"
            if (!ftp.changeWorkingDirectory(path)) {
                ftp.makeDirectory(path)
                ftp.changeWorkingDirectory(path)
            }
        }
        // Return to root so absolute remote names resolve predictably.
        ftp.changeWorkingDirectory("/")
    }
}
