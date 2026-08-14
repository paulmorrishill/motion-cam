package com.motioncam.settings

import org.json.JSONException
import org.json.JSONObject

/**
 * Serialises [Settings] to/from the compact JSON payload carried by a config QR code.
 *
 * Pure Kotlin (no Android deps) so it is unit-testable on the JVM. The same field
 * names are used by the standalone HTML generator page (`tools/qr-config.html`).
 *
 * Decode merges onto a supplied `current` snapshot: any field the QR omits keeps its
 * current value, so a QR carrying only (say) the FTP keys updates just those. Unknown
 * keys are ignored; a field with the wrong type falls back to the current value rather
 * than crashing the scan.
 */
object SettingsCodec {

    /** Bump when the payload shape changes incompatibly. */
    const val VERSION = 1

    private const val K_V = "_v"
    private const val K_DEVICE = "deviceName"
    private const val K_RES_W = "resolutionWidth"
    private const val K_RES_H = "resolutionHeight"
    private const val K_SENS = "motionSensitivity"
    private const val K_VID_ROT = "videoRotationDegrees"
    private const val K_TIMEOUT = "noMotionTimeoutSec"
    private const val K_MIN_MOVE = "minMovementSec"
    private const val K_MAXSIZE = "maxFileSizeMb"
    private const val K_FTP_HOST = "ftpHost"
    private const val K_FTP_PORT = "ftpPort"
    private const val K_FTP_USER = "ftpUser"
    private const val K_FTP_PASS = "ftpPassword"
    private const val K_FTP_PATH = "ftpPath"
    private const val K_STORAGE_LOW = "storageLowPercent"
    private const val K_BATT_LOW = "batteryLowPercent"
    private const val K_RETENTION = "retentionDays"

    fun encode(s: Settings): String {
        val o = JSONObject()
        o.put(K_V, VERSION)
        o.put(K_DEVICE, s.deviceName)
        o.put(K_RES_W, s.resolutionWidth)
        o.put(K_RES_H, s.resolutionHeight)
        o.put(K_SENS, s.motionSensitivity)
        o.put(K_VID_ROT, s.videoRotationDegrees)
        o.put(K_TIMEOUT, s.noMotionTimeoutSec)
        o.put(K_MIN_MOVE, s.minMovementSec)
        o.put(K_MAXSIZE, s.maxFileSizeMb)
        o.put(K_FTP_HOST, s.ftpHost)
        o.put(K_FTP_PORT, s.ftpPort)
        o.put(K_FTP_USER, s.ftpUser)
        o.put(K_FTP_PASS, s.ftpPassword)
        o.put(K_FTP_PATH, s.ftpPath)
        o.put(K_STORAGE_LOW, s.storageLowPercent)
        o.put(K_BATT_LOW, s.batteryLowPercent)
        o.put(K_RETENTION, s.retentionDays)
        return o.toString()
    }

    /**
     * Parse [json] and merge it onto [current]. Omitted / unparseable fields keep their
     * value from [current].
     *
     * @throws IllegalArgumentException if [json] is not a JSON object.
     */
    fun decode(json: String, current: Settings): Settings {
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Not a valid config QR payload", e)
        }
        // Require our version marker so an arbitrary QR (any JSON object, or a future
        // incompatible schema) is rejected rather than silently merged onto current.
        if (o.optInt(K_V, -1) != VERSION) {
            throw IllegalArgumentException("Not a MotionCam config QR (version ${o.opt(K_V)})")
        }
        return current.copy(
            deviceName = o.optString(K_DEVICE, current.deviceName),
            resolutionWidth = o.optInt(K_RES_W, current.resolutionWidth),
            resolutionHeight = o.optInt(K_RES_H, current.resolutionHeight),
            motionSensitivity = o.optInt(K_SENS, current.motionSensitivity),
            videoRotationDegrees = o.optInt(K_VID_ROT, current.videoRotationDegrees),
            noMotionTimeoutSec = o.optInt(K_TIMEOUT, current.noMotionTimeoutSec),
            minMovementSec = o.optInt(K_MIN_MOVE, current.minMovementSec),
            maxFileSizeMb = o.optInt(K_MAXSIZE, current.maxFileSizeMb),
            ftpHost = o.optString(K_FTP_HOST, current.ftpHost),
            ftpPort = o.optInt(K_FTP_PORT, current.ftpPort),
            ftpUser = o.optString(K_FTP_USER, current.ftpUser),
            ftpPassword = o.optString(K_FTP_PASS, current.ftpPassword),
            ftpPath = o.optString(K_FTP_PATH, current.ftpPath),
            storageLowPercent = o.optInt(K_STORAGE_LOW, current.storageLowPercent),
            batteryLowPercent = o.optInt(K_BATT_LOW, current.batteryLowPercent),
            retentionDays = o.optInt(K_RETENTION, current.retentionDays)
        )
    }
}
