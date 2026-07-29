package com.motioncam.ui

import android.view.SurfaceHolder
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.motioncam.camera.AutoFitSurfaceView
import com.motioncam.service.CameraService

/**
 * Camera preview backed by an [AutoFitSurfaceView] (a SurfaceView), which the
 * system compositor rotates for free and which center-crops to the camera aspect
 * ratio — so the preview is never warped or wrongly rotated (no transform matrix).
 * The Surface's lifecycle drives attach/detach: on create/change we hand the
 * Surface to the service, on destroy we clear it. Because the app is locked to
 * landscape, the buffer and view are both landscape.
 */
@Composable
fun CameraPreview(
    service: CameraService?,
    onTap: (x: Float, y: Float, w: Int, h: Int) -> Unit,
    onLongPress: (x: Float, y: Float, w: Int, h: Int) -> Unit,
    onZoom: (factor: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = remember { intArrayOf(1, 1) }
    var currentSurface by remember { mutableStateOf<android.view.Surface?>(null) }

    // Attach the preview once both the Surface and the service are ready. This
    // also re-attaches after returning from another screen (new Surface).
    LaunchedEffect(service, currentSurface) {
        val s = currentSurface
        if (service != null && s != null) service.setPreviewSurface(s)
    }

    AndroidView(
        modifier = modifier
            .pointerInput(Unit) {
                // Pinch to zoom; single-finger gestures yield zoom≈1 (no-op).
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) onZoom(zoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o -> onTap(o.x, o.y, dims[0], dims[1]) },
                    onLongPress = { o -> onLongPress(o.x, o.y, dims[0], dims[1]) }
                )
            },
        factory = { ctx ->
            AutoFitSurfaceView(ctx).apply {
                service?.previewSize()?.let { setAspectRatio(it.width, it.height) }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        dims[0] = width.coerceAtLeast(1)
                        dims[1] = height.coerceAtLeast(1)
                        currentSurface = h.surface
                    }

                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                        dims[0] = width.coerceAtLeast(1)
                        dims[1] = height.coerceAtLeast(1)
                        currentSurface = h.surface
                    }

                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        currentSurface = null
                        service?.clearPreview()
                    }
                })
            }
        },
        update = { view ->
            service?.previewSize()?.let { view.setAspectRatio(it.width, it.height) }
        }
    )
}
