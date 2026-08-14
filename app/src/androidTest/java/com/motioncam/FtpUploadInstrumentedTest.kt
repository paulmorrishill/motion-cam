package com.motioncam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.motioncam.upload.FtpUploader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockftpserver.fake.FakeFtpServer
import org.mockftpserver.fake.UserAccount
import org.mockftpserver.fake.filesystem.DirectoryEntry
import org.mockftpserver.fake.filesystem.FileEntry
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem
import java.io.File

/**
 * True end-to-end FTP upload test: starts a real (fake) FTP server on the
 * emulator's loopback, uploads a file with the production [FtpUploader]
 * (Apache Commons Net client) and verifies the exact bytes arrive on the server.
 */
@RunWith(AndroidJUnit4::class)
class FtpUploadInstrumentedTest {

    private lateinit var server: FakeFtpServer
    private lateinit var fs: UnixFakeFileSystem
    private var port: Int = 0

    private val user = "camuser"
    private val pass = "campass"

    @Before
    fun startServer() {
        fs = UnixFakeFileSystem().apply {
            add(DirectoryEntry("/"))
            add(DirectoryEntry("/incoming"))
        }
        server = FakeFtpServer().apply {
            fileSystem = fs
            addUserAccount(UserAccount(user, pass, "/"))
            serverControlPort = 0 // random free port
            start()
        }
        port = server.serverControlPort
    }

    @After
    fun stopServer() {
        if (this::server.isInitialized) server.stop()
    }

    private fun tempFileOf(sizeBytes: Int): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File.createTempFile("uitest", ".dat", ctx.cacheDir)
        f.outputStream().use { out ->
            val chunk = ByteArray(1024) { (it % 251).toByte() }
            var written = 0
            while (written < sizeBytes) {
                val n = minOf(chunk.size, sizeBytes - written)
                out.write(chunk, 0, n)
                written += n
            }
        }
        return f
    }

    @Test
    fun uploadsFileAndBytesMatchOnServer() = runBlocking {
        val file = tempFileOf(200_000)
        var lastProgress = 0L
        val result = FtpUploader().upload(
            file = file,
            config = FtpUploader.Config("127.0.0.1", port, user, pass, "/incoming"),
            onProgress = { lastProgress = it }
        )

        assertThat(result).isInstanceOf(FtpUploader.Result.Success::class.java)
        assertThat(lastProgress).isEqualTo(file.length())

        val entry = fs.getEntry("/incoming/${file.name}") as FileEntry
        assertThat(entry.size).isEqualTo(file.length())
        val stored = entry.createInputStream().use { it.readBytes() }
        assertThat(stored.size.toLong()).isEqualTo(file.length())
        assertThat(stored).isEqualTo(file.readBytes())
    }

    @Test
    fun wrongPasswordFails() = runBlocking {
        val file = tempFileOf(1_000)
        val result = FtpUploader().upload(
            file = file,
            config = FtpUploader.Config("127.0.0.1", port, user, "wrong-password", "/incoming"),
            onProgress = { }
        )
        assertThat(result).isInstanceOf(FtpUploader.Result.Failure::class.java)
    }

    @Test
    fun testConnection_succeedsWithValidConfig() = runBlocking {
        val result = FtpUploader().test(
            FtpUploader.Config("127.0.0.1", port, user, pass, "/incoming")
        )
        assertThat(result).isInstanceOf(FtpUploader.Result.Success::class.java)
        // Probe file must be cleaned up, not left behind.
        assertThat(fs.exists("/incoming/.motioncam_test")).isFalse()
    }

    @Test
    fun testConnection_failsOnWrongPassword() = runBlocking {
        val result = FtpUploader().test(
            FtpUploader.Config("127.0.0.1", port, user, "nope", "/incoming")
        )
        assertThat(result).isInstanceOf(FtpUploader.Result.Failure::class.java)
    }

    @Test
    fun createsRemoteDirectoryWhenMissing() = runBlocking {
        val file = tempFileOf(2_000)
        val result = FtpUploader().upload(
            file = file,
            config = FtpUploader.Config("127.0.0.1", port, user, pass, "/incoming/2026/sub"),
            onProgress = { }
        )
        assertThat(result).isInstanceOf(FtpUploader.Result.Success::class.java)
        assertThat(fs.exists("/incoming/2026/sub/${file.name}")).isTrue()
    }
}
