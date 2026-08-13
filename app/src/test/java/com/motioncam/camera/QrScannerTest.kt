package com.motioncam.camera

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Test

class QrScannerTest {

    /** Render a QR string to a luma (Y) plane: black module = 0, white = 255. */
    private fun renderLuma(text: String, size: Int, rowStride: Int = size): ByteArray {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val luma = ByteArray(rowStride * size) { 255.toByte() }
        for (y in 0 until size) {
            for (x in 0 until size) {
                luma[y * rowStride + x] = if (matrix.get(x, y)) 0 else 255.toByte()
            }
        }
        return luma
    }

    @Test
    fun decode_readsBackAnEncodedPayload() {
        val payload = """{"_v":1,"deviceName":"garage","ftpHost":"192.168.1.50"}"""
        val luma = renderLuma(payload, size = 400)

        val decoded = QrScanner().decode(luma, width = 400, height = 400, rowStride = 400)

        assertThat(decoded).isEqualTo(payload)
    }

    @Test
    fun decode_handlesPaddedRowStride() {
        val payload = "hello-motioncam"
        // Row stride wider than the visible width, as ImageReader often delivers.
        val luma = renderLuma(payload, size = 300, rowStride = 320)

        val decoded = QrScanner().decode(luma, width = 300, height = 300, rowStride = 320)

        assertThat(decoded).isEqualTo(payload)
    }

    @Test
    fun decode_returnsNullWhenNoQrPresent() {
        // Uniform grey frame — no code.
        val luma = ByteArray(320 * 240) { 128.toByte() }
        val decoded = QrScanner().decode(luma, width = 320, height = 240, rowStride = 320)
        assertThat(decoded).isNull()
    }

    @Test
    fun decode_returnsNullWhenBufferTooSmall() {
        val luma = ByteArray(10)
        val decoded = QrScanner().decode(luma, width = 320, height = 240, rowStride = 320)
        assertThat(decoded).isNull()
    }
}
