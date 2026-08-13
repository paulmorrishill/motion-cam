package com.motioncam.camera

/**
 * Picks which back cameras to expose as a lens toggle.
 *
 * Samsung (and most) devices lock the Camera2 zoom-ratio range of the main back
 * camera to a >= 1.0 floor, so ultra-wide is unreachable by zooming out — it is a
 * separate physical camera with a shorter focal length. This chooses the "Main" lens
 * (the device's default back camera) and, if present, the ultra-wide (the back camera
 * with the smallest focal length) for a two-way toggle.
 *
 * Pure Kotlin (no Android types) so it is fully unit-testable.
 */
object LensChooser {

    /** A back camera the app can open, with its shortest focal length (mm). */
    data class BackLens(val cameraId: String, val focalLengthMm: Float)

    /** A selectable lens for the UI toggle. */
    data class Lens(val cameraId: String, val label: String)

    /**
     * @param defaultBackId the device's primary back camera id (what the app opens by
     *   default).
     * @param backLenses all openable back cameras with their focal lengths.
     * @return the toggle list: always the Main lens; plus a "Wide" lens when a distinct
     *   ultra-wide (shortest focal length) camera exists. Order is stable: Main, Wide.
     */
    fun toggleLenses(defaultBackId: String, backLenses: List<BackLens>): List<Lens> {
        val main = Lens(defaultBackId, "Main")
        val wide = backLenses.minByOrNull { it.focalLengthMm }
        return if (wide != null && wide.cameraId != defaultBackId) {
            listOf(main, Lens(wide.cameraId, "Wide"))
        } else {
            listOf(main)
        }
    }
}
