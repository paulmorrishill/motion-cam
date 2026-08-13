package com.motioncam.camera

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Decodes a QR code out of a camera luma (Y) plane.
 *
 * The recorder service already receives a low-resolution luma frame for motion
 * detection; feeding those same frames here lets us scan a config QR without opening
 * a second camera client (the Camera2 device is held exclusively for the app's whole
 * lifetime, so a separate scanner Activity could not get the camera).
 *
 * Pure JVM (ZXing core is plain Java) so it is unit-testable via an encode→render→decode
 * round trip. Not thread-safe: call from a single analysis thread (the service does).
 */
class QrScanner {

    private val reader = QRCodeReader()
    private val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)

    /**
     * Attempt to decode a QR code from a luma plane. Returns the decoded text, or null
     * if no QR code is present in this frame (or the buffer is too small to be safe).
     *
     * @param luma      Y-plane bytes (brightness; higher = brighter).
     * @param width     visible image width in pixels.
     * @param height    visible image height in pixels.
     * @param rowStride bytes per row (>= width; rows may be padded).
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        // Largest index PlanarYUVLuminanceSource will read; bail rather than risk OOB.
        val maxIndex = (height - 1).toLong() * rowStride + width
        if (maxIndex > luma.size) return null

        return try {
            val source = PlanarYUVLuminanceSource(
                luma, rowStride, height, 0, 0, width, height, false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            reader.decode(bitmap, hints).text
        } catch (e: ReaderException) {
            // No QR found in this frame — normal, keep scanning subsequent frames.
            null
        } finally {
            reader.reset()
        }
    }
}
