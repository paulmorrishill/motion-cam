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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motioncam.service.UiState
import com.motioncam.upload.RecentFile
import com.motioncam.upload.UploadItem
import com.motioncam.upload.UploadStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UploadsScreen(
    ui: UiState,
    onBack: () -> Unit,
    onTestFtp: () -> Unit = {},
    onForceUpload: () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Uploads", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onBack) { Text("Back") }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onTestFtp,
                enabled = !ui.ftpTesting,
                modifier = Modifier.weight(1f)
            ) { Text(if (ui.ftpTesting) "Testing…" else "Test FTP") }
            Button(
                onClick = onForceUpload,
                modifier = Modifier.weight(1f)
            ) { Text("Upload now") }
        }
        if (ui.ftpTestResult != null) {
            val ok = ui.ftpTestResult.startsWith("FTP OK")
            Text(
                ui.ftpTestResult,
                fontSize = 12.sp,
                color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionHeader("Queue (${ui.uploadQueue.size})") }
            if (ui.uploadQueue.isEmpty()) {
                item { MutedText("Nothing queued.") }
            }
            items(ui.uploadQueue) { item ->
                UploadRow(item)
                HorizontalDivider()
            }

            item { SectionHeader("Recent") }
            if (ui.recentFiles.isEmpty()) {
                item { MutedText("No recent activity.") }
            }
            items(ui.recentFiles) { rf ->
                RecentRow(rf)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun MutedText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun UploadRow(item: UploadItem) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                item.name,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(item.status.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = statusColor(item.status))
        }
        if (item.status == UploadStatus.UPLOADING) {
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Text(
                "${item.uploadedBytes / (1024 * 1024)} / ${item.sizeBytes / (1024 * 1024)} MB",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.error != null) {
            Text(item.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RecentRow(rf: RecentFile) {
    val fmt = remember0()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            rf.name,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${rf.event.name.lowercase()} · ${fmt.format(Date(rf.timeMillis))}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (rf.event == RecentFile.Event.DELETED) Color(0xFFE65100) else Color(0xFF2E7D32)
        )
    }
}

private fun statusColor(status: UploadStatus): Color = when (status) {
    UploadStatus.QUEUED -> Color(0xFF616161)
    UploadStatus.UPLOADING -> Color(0xFF0277BD)
    UploadStatus.DONE -> Color(0xFF2E7D32)
    UploadStatus.FAILED -> Color(0xFFC62828)
}

private fun remember0() = SimpleDateFormat("dd/MM HH:mm:ss", Locale.UK)
