package com.motioncam.motion

import kotlin.math.abs

/**
 * Frame-difference motion detector operating on the luminance (Y) plane of
 * successive camera frames.
 *
 * The algorithm is deliberately simple and cheap so it can run continuously on
 * every preview frame:
 *   1. Down-sample the incoming luma to a small grid (default 32x24 cells).
 *   2. Compare each cell's average brightness against the previous frame.
 *   3. Count cells whose change exceeds [pixelDelta].
 *   4. Motion is "present" when the fraction of changed cells exceeds a
 *      threshold derived from [sensitivity].
 *
 * Pure Kotlin (no Android types) so it is fully unit-testable.
 *
 * @param sensitivity 0..100. Higher = more sensitive (smaller change triggers).
 */
class MotionDetector(
    sensitivity: Int = 50,
    private val gridCols: Int = 32,
    private val gridRows: Int = 24,
    private val pixelDelta: Int = 18
) {
    // Fraction of cells that must change for motion to register.
    // sensitivity 0   -> require ~12% of cells changed (very insensitive)
    // sensitivity 100 -> require ~0.4% of cells changed (very sensitive)
    private var changedCellFraction: Double = fractionFor(sensitivity)

    private var previous: IntArray? = null
    private val cellCount = gridCols * gridRows

    fun setSensitivity(sensitivity: Int) {
        changedCellFraction = fractionFor(sensitivity)
    }

    /** Clears history; next frame establishes a new baseline (no motion). */
    fun reset() {
        previous = null
    }

    /**
     * Feeds one luma frame.
     *
     * @param luma   row-major Y plane bytes (values 0..255 as unsigned).
     * @param width  frame width in pixels.
     * @param height frame height in pixels.
     * @param rowStride bytes per row in [luma] (>= width; padding is skipped).
     * @return a [Result] describing this frame.
     */
    fun submit(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): Result {
        val grid = downsample(luma, width, height, rowStride)
        val prev = previous
        previous = grid
        if (prev == null) {
            return Result(motion = false, changedCells = 0, cellCount = cellCount, score = 0.0)
        }
        var changed = 0
        for (i in grid.indices) {
            if (abs(grid[i] - prev[i]) >= pixelDelta) changed++
        }
        val score = changed.toDouble() / cellCount
        val motion = score >= changedCellFraction
        return Result(motion = motion, changedCells = changed, cellCount = cellCount, score = score)
    }

    private fun downsample(luma: ByteArray, width: Int, height: Int, rowStride: Int): IntArray {
        val out = IntArray(cellCount)
        val counts = IntArray(cellCount)
        // Map each source pixel to a grid cell and accumulate averages.
        // Sample a subset of rows/cols for speed on large frames.
        val colStep = maxOf(1, width / (gridCols * 4))
        val rowStep = maxOf(1, height / (gridRows * 4))
        var y = 0
        while (y < height) {
            val cellRow = (y * gridRows) / height
            val rowBase = y * rowStride
            var x = 0
            while (x < width) {
                val cellCol = (x * gridCols) / width
                val cell = cellRow * gridCols + cellCol
                val v = luma[rowBase + x].toInt() and 0xFF
                out[cell] += v
                counts[cell]++
                x += colStep
            }
            y += rowStep
        }
        for (i in out.indices) {
            if (counts[i] > 0) out[i] /= counts[i]
        }
        return out
    }

    private fun fractionFor(sensitivity: Int): Double {
        val s = sensitivity.coerceIn(0, 100)
        // Linear interpolation between insensitive (0.12) and sensitive (0.004).
        val high = 0.12
        val low = 0.004
        return high - (high - low) * (s / 100.0)
    }

    data class Result(
        val motion: Boolean,
        val changedCells: Int,
        val cellCount: Int,
        val score: Double
    )
}
