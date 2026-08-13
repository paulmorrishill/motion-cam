package com.motioncam.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed settings store exposed as an observable [StateFlow].
 * A process-wide singleton so the service and UI share one source of truth.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("motioncam_settings", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow.asStateFlow()

    val current: Settings get() = _flow.value

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            deviceName = prefs.getString(K_DEVICE, d.deviceName) ?: d.deviceName,
            resolutionWidth = prefs.getInt(K_RES_W, d.resolutionWidth),
            resolutionHeight = prefs.getInt(K_RES_H, d.resolutionHeight),
            motionSensitivity = prefs.getInt(K_SENS, d.motionSensitivity),
            videoRotationDegrees = prefs.getInt(K_VID_ROT, d.videoRotationDegrees),
            noMotionTimeoutSec = prefs.getInt(K_TIMEOUT, d.noMotionTimeoutSec),
            maxFileSizeMb = prefs.getInt(K_MAXSIZE, d.maxFileSizeMb),
            ftpHost = prefs.getString(K_FTP_HOST, d.ftpHost) ?: d.ftpHost,
            ftpPort = prefs.getInt(K_FTP_PORT, d.ftpPort),
            ftpUser = prefs.getString(K_FTP_USER, d.ftpUser) ?: d.ftpUser,
            ftpPassword = prefs.getString(K_FTP_PASS, d.ftpPassword) ?: d.ftpPassword,
            ftpPath = prefs.getString(K_FTP_PATH, d.ftpPath) ?: d.ftpPath,
            storageLowPercent = prefs.getInt(K_STORAGE_LOW, d.storageLowPercent),
            batteryLowPercent = prefs.getInt(K_BATT_LOW, d.batteryLowPercent),
            retentionDays = prefs.getInt(K_RETENTION, d.retentionDays),
            useUltraWide = prefs.getBoolean(K_ULTRA_WIDE, d.useUltraWide)
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_flow.value)
        prefs.edit()
            .putString(K_DEVICE, next.deviceName)
            .putInt(K_RES_W, next.resolutionWidth)
            .putInt(K_RES_H, next.resolutionHeight)
            .putInt(K_SENS, next.motionSensitivity)
            .putInt(K_VID_ROT, next.videoRotationDegrees)
            .putInt(K_TIMEOUT, next.noMotionTimeoutSec)
            .putInt(K_MAXSIZE, next.maxFileSizeMb)
            .putString(K_FTP_HOST, next.ftpHost)
            .putInt(K_FTP_PORT, next.ftpPort)
            .putString(K_FTP_USER, next.ftpUser)
            .putString(K_FTP_PASS, next.ftpPassword)
            .putString(K_FTP_PATH, next.ftpPath)
            .putInt(K_STORAGE_LOW, next.storageLowPercent)
            .putInt(K_BATT_LOW, next.batteryLowPercent)
            .putInt(K_RETENTION, next.retentionDays)
            .putBoolean(K_ULTRA_WIDE, next.useUltraWide)
            .apply()
        _flow.value = next
    }

    companion object {
        private const val K_DEVICE = "device_name"
        private const val K_RES_W = "res_w"
        private const val K_RES_H = "res_h"
        private const val K_SENS = "sensitivity"
        private const val K_VID_ROT = "video_rotation_deg"
        private const val K_TIMEOUT = "no_motion_timeout"
        private const val K_MAXSIZE = "max_file_mb"
        private const val K_FTP_HOST = "ftp_host"
        private const val K_FTP_PORT = "ftp_port"
        private const val K_FTP_USER = "ftp_user"
        private const val K_FTP_PASS = "ftp_pass"
        private const val K_FTP_PATH = "ftp_path"
        private const val K_STORAGE_LOW = "storage_low_pct"
        private const val K_BATT_LOW = "batt_low_pct"
        private const val K_RETENTION = "retention_days"
        private const val K_ULTRA_WIDE = "use_ultra_wide"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
