package com.motioncam.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsCodecTest {

    private val sample = Settings(
        deviceName = "garage",
        resolutionWidth = 3840,
        resolutionHeight = 2160,
        motionSensitivity = 72,
        videoRotationDegrees = 180,
        noMotionTimeoutSec = 45,
        maxFileSizeMb = 1500,
        ftpHost = "192.168.1.50",
        ftpPort = 2121,
        ftpUser = "cam",
        ftpPassword = "s3cret",
        ftpPath = "/uploads",
        storageLowPercent = 15,
        batteryLowPercent = 25,
        retentionDays = 14
    )

    @Test
    fun encodeThenDecode_roundTripsIdentically() {
        val json = SettingsCodec.encode(sample)
        val decoded = SettingsCodec.decode(json, Settings())
        assertThat(decoded).isEqualTo(sample)
    }

    @Test
    fun encode_includesSchemaVersion() {
        val json = SettingsCodec.encode(sample)
        assertThat(json).contains("\"_v\"")
    }

    @Test
    fun decode_partialJson_keepsCurrentForOmittedFields() {
        val current = Settings(deviceName = "old", ftpHost = "10.0.0.1", retentionDays = 30)
        // QR carrying only FTP host + user; everything else must fall back to current.
        val json = """{"_v":1,"ftpHost":"10.0.0.9","ftpUser":"newuser"}"""
        val decoded = SettingsCodec.decode(json, current)

        assertThat(decoded.ftpHost).isEqualTo("10.0.0.9")
        assertThat(decoded.ftpUser).isEqualTo("newuser")
        // Untouched fields retain the current values.
        assertThat(decoded.deviceName).isEqualTo("old")
        assertThat(decoded.retentionDays).isEqualTo(30)
    }

    @Test
    fun decode_ignoresUnknownKeys() {
        val json = """{"_v":1,"deviceName":"cam","somethingNew":"ignore-me"}"""
        val decoded = SettingsCodec.decode(json, Settings())
        assertThat(decoded.deviceName).isEqualTo("cam")
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_malformedJson_throwsIllegalArgument() {
        SettingsCodec.decode("not json at all", Settings())
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_jsonArrayInsteadOfObject_throwsIllegalArgument() {
        SettingsCodec.decode("""["a","b"]""", Settings())
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_jsonObjectWithoutVersion_throwsIllegalArgument() {
        // A valid JSON object that is not one of our payloads must be rejected, not
        // silently merged onto current.
        SettingsCodec.decode("""{"deviceName":"cam","foo":1}""", Settings())
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_unsupportedVersion_throwsIllegalArgument() {
        SettingsCodec.decode("""{"_v":2,"deviceName":"cam"}""", Settings())
    }

    @Test
    fun decode_wrongTypedField_keepsCurrentValue() {
        // ftpPort given as a non-numeric string must not crash; falls back to current.
        val current = Settings(ftpPort = 21)
        val json = """{"_v":1,"ftpPort":"notanumber"}"""
        val decoded = SettingsCodec.decode(json, current)
        assertThat(decoded.ftpPort).isEqualTo(21)
    }
}
