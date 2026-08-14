package com.motioncam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.motioncam.settings.Settings
import com.motioncam.settings.SettingsStore

@Composable
fun SettingsScreen(
    activity: MainActivity,
    onScan: () -> Unit,
    onBack: () -> Unit,
    initialOverride: Settings? = null
) {
    val store = remember { SettingsStore.get(activity) }
    // When a config QR has just been scanned, seed the fields from it (still unsaved
    // and fully editable) instead of the persisted values.
    val initial = remember { initialOverride ?: store.current }

    var deviceName by remember { mutableStateOf(initial.deviceName) }
    var resW by remember { mutableStateOf(initial.resolutionWidth.toString()) }
    var resH by remember { mutableStateOf(initial.resolutionHeight.toString()) }
    var sensitivity by remember { mutableStateOf(initial.motionSensitivity.toFloat()) }
    var videoRot by remember { mutableStateOf(initial.videoRotationDegrees.toString()) }
    var timeout by remember { mutableStateOf(initial.noMotionTimeoutSec.toString()) }
    var minMovement by remember { mutableStateOf(initial.minMovementSec.toString()) }
    var maxSize by remember { mutableStateOf(initial.maxFileSizeMb.toString()) }
    var ftpHost by remember { mutableStateOf(initial.ftpHost) }
    var ftpPort by remember { mutableStateOf(initial.ftpPort.toString()) }
    var ftpUser by remember { mutableStateOf(initial.ftpUser) }
    var ftpPass by remember { mutableStateOf(initial.ftpPassword) }
    var ftpPath by remember { mutableStateOf(initial.ftpPath) }
    var storageLow by remember { mutableStateOf(initial.storageLowPercent.toString()) }
    var batteryLow by remember { mutableStateOf(initial.batteryLowPercent.toString()) }
    var retention by remember { mutableStateOf(initial.retentionDays.toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)

        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text("Scan config QR")
        }
        if (initialOverride != null) {
            Text(
                "Loaded from QR — review and Save to apply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        field("Device name", deviceName) { deviceName = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            numField("Width (0=auto)", resW, Modifier.weight(1f)) { resW = it }
            numField("Height (0=auto)", resH, Modifier.weight(1f)) { resH = it }
        }
        Text("Motion sensitivity: ${sensitivity.toInt()}")
        Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = 0f..100f)
        numField("Video rotation deg (0=landscape, 180=flip, 90/270=portrait, -1=auto)", videoRot) { videoRot = it }
        numField("No-motion timeout (sec)", timeout) { timeout = it }
        numField("Min motion to keep clip (sec, 0 = off)", minMovement) { minMovement = it }
        numField("Max file size (MB)", maxSize) { maxSize = it }

        Text("FTP upload", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        field("FTP host / IP", ftpHost) { ftpHost = it }
        numField("FTP port", ftpPort) { ftpPort = it }
        field("FTP username", ftpUser) { ftpUser = it }
        OutlinedTextField(
            value = ftpPass,
            onValueChange = { ftpPass = it },
            label = { Text("FTP password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        field("FTP remote path", ftpPath) { ftpPath = it }

        Text("Warnings & retention", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        numField("Storage low threshold (%)", storageLow) { storageLow = it }
        numField("Battery low threshold (%)", batteryLow) { batteryLow = it }
        numField("Delete uploaded after (days)", retention) { retention = it }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = {
                    store.update { s ->
                        s.copy(
                            deviceName = deviceName,
                            resolutionWidth = resW.toIntOrNull() ?: s.resolutionWidth,
                            resolutionHeight = resH.toIntOrNull() ?: s.resolutionHeight,
                            motionSensitivity = sensitivity.toInt(),
                            videoRotationDegrees = videoRot.toIntOrNull() ?: s.videoRotationDegrees,
                            noMotionTimeoutSec = timeout.toIntOrNull() ?: s.noMotionTimeoutSec,
                            minMovementSec = minMovement.toIntOrNull() ?: s.minMovementSec,
                            maxFileSizeMb = maxSize.toIntOrNull() ?: s.maxFileSizeMb,
                            ftpHost = ftpHost.trim(),
                            ftpPort = ftpPort.toIntOrNull() ?: s.ftpPort,
                            ftpUser = ftpUser,
                            ftpPassword = ftpPass,
                            ftpPath = if (ftpPath.isBlank()) "/" else ftpPath.trim(),
                            storageLowPercent = storageLow.toIntOrNull() ?: s.storageLowPercent,
                            batteryLowPercent = batteryLow.toIntOrNull() ?: s.batteryLowPercent,
                            retentionDays = retention.toIntOrNull() ?: s.retentionDays
                        )
                    }
                    onBack()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Save") }
        }

        // Fully stop the app (stops recording, releases the camera, stops the
        // service so it is not restarted).
        Button(
            onClick = { activity.quitApp() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Stop recording & exit app") }
    }
}

@Composable
private fun field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun numField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}
