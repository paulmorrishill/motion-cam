package com.motioncam.settings

/** Immutable snapshot of all user-configurable settings. */
data class Settings(
    val deviceName: String = "camera",
    // 0 => auto (pick best available). Otherwise an exact recording resolution.
    val resolutionWidth: Int = 1920,
    val resolutionHeight: Int = 1080,
    val motionSensitivity: Int = 50,        // 0..100
    val noMotionTimeoutSec: Int = 60,
    val maxFileSizeMb: Int = 1900,          // ~2GB rollover
    val ftpHost: String = "",
    val ftpPort: Int = 21,
    val ftpUser: String = "",
    val ftpPassword: String = "",
    val ftpPath: String = "/",
    val storageLowPercent: Int = 10,
    val batteryLowPercent: Int = 30,
    val retentionDays: Int = 7
) {
    val resolutionIsAuto: Boolean get() = resolutionWidth <= 0 || resolutionHeight <= 0
    val ftpConfigured: Boolean get() = ftpHost.isNotBlank()
}
