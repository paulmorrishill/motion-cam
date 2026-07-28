package com.motioncam.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.motioncam.service.CameraService

/**
 * Camera preview backed by a [TextureView]. The service owns the camera and is
 * handed this view's [SurfaceTexture] exactly once per (service, texture) pair
 * so the capture session is not thrashed on recomposition.
 */
@Composable
fun CameraPreview(
    service: CameraService?,
    onTap: (x: Float, y: Float, w: Int, h: Int) -> Unit,
    onLongPress: (x: Float, y: Float, w: Int, h: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = remember { intArrayOf(1, 1) }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }

    // Attach the surface once when both the texture and the service are ready.
    LaunchedEffect(service, texture) {
        val st = texture
        if (service != null && st != null) service.setPreviewTexture(st)
    }

    AndroidView(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { o -> onTap(o.x, o.y, dims[0], dims[1]) },
                onLongPress = { o -> onLongPress(o.x, o.y, dims[0], dims[1]) }
            )
        },
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        dims[0] = width.coerceAtLeast(1)
                        dims[1] = height.coerceAtLeast(1)
                        texture = st
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        dims[0] = width.coerceAtLeast(1)
                        dims[1] = height.coerceAtLeast(1)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        texture = null
                        service?.clearPreview()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        }
    )
}
