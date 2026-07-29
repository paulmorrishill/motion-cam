package com.motioncam.camera

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * A [SurfaceView] that scales itself to the aspect ratio of the camera preview
 * buffer (center-crop) instead of stretching it. This is Google's camera-samples
 * approach: SurfaceView gets free rotation from the system compositor and this
 * onMeasure removes the warp — so no transform matrix is ever needed.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var aspectRatio = 0f
    private var bufferWidth = 0
    private var bufferHeight = 0

    /** [width]/[height] are the camera buffer dimensions in sensor space. No-ops if
     *  unchanged so it can be called freely from Compose recomposition. */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }
        if (width == bufferWidth && height == bufferHeight) return
        bufferWidth = width
        bufferHeight = height
        aspectRatio = width.toFloat() / height.toFloat()
        holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (aspectRatio == 0f) {
            setMeasuredDimension(width, height)
        } else {
            val actualRatio = if (width > height) aspectRatio else 1f / aspectRatio
            val newWidth: Int
            val newHeight: Int
            if (width < height * actualRatio) {
                newHeight = height
                newWidth = (height * actualRatio).roundToInt()
            } else {
                newWidth = width
                newHeight = (width / actualRatio).roundToInt()
            }
            setMeasuredDimension(newWidth, newHeight)
        }
    }
}
