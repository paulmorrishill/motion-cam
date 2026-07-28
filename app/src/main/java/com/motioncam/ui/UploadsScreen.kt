package com.motioncam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
fun UploadsScreen(ui: UiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Uploads", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onBack) { Text("Back") }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "Queue (${ui.uploadQueue.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            if (ui.uploadQueue.isEmpty()) {
                item { Text("Nothing queued.", color = Color.Gray) }
            }
            items(ui.uploadQueue) { item -> UploadRow(item) }

            item {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            if (ui.recentFiles.isEmpty()) {
                item { Text("No recent activity.", color = Color.Gray) }
            }
            items(ui.recentFiles) { rf -> RecentRow(rf) }
        }
    }
}

@Composable
private fun UploadRow(item: UploadItem) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.name, fontSize = 13.sp, color = Color.White)
            Text(item.status.name, fontSize = 12.sp, color = statusColor(item.status))
        }
        if (item.status == UploadStatus.UPLOADING) {
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            Text(
                "${item.uploadedBytes / (1024 * 1024)} / ${item.sizeBytes / (1024 * 1024)} MB",
                fontSize = 11.sp, color = Color.Gray
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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(rf.name, fontSize = 12.sp, color = Color.White)
        Text(
            "${rf.event.name.lowercase()} · ${fmt.format(Date(rf.timeMillis))}",
            fontSize = 11.sp,
            color = if (rf.event == RecentFile.Event.DELETED) Color(0xFFFF8A65) else Color(0xFF81C784)
        )
    }
}

private fun statusColor(status: UploadStatus): Color = when (status) {
    UploadStatus.QUEUED -> Color(0xFFB0BEC5)
    UploadStatus.UPLOADING -> Color(0xFF4FC3F7)
    UploadStatus.DONE -> Color(0xFF81C784)
    UploadStatus.FAILED -> Color(0xFFEF5350)
}

private fun remember0() = SimpleDateFormat("dd/MM HH:mm:ss", Locale.UK)
